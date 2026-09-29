pub mod algo;
mod boxes;
mod buoyancy;
mod collider;
mod config;
mod contraptions;
mod dispatcher;
mod event_handler;
mod groups;
mod hooks;
mod joints;
mod prec;
mod rope;
mod scene;
mod shapes;
mod voxel_collider;

// 鈽?2026-09-02 (0.35.3 f64)锛歝rate 绾х被鍨嬪埆鍚?鈥斺€?f64 涓?Vec3/Quat/Pose3/IVec3
//   鍏ㄩ儴鏄犲皠鍒?f64 鐗堟湰锛坓lamx::* 鏄浐瀹?f32锛屽繀椤荤敤 math::* 鎵嶉殢 f64锛夈€?
pub type Vec3  = rapier3d::math::Vector;
pub type Quat  = rapier3d::math::Rotation;
pub type Pose3 = rapier3d::math::Pose;
pub type IVec3 = rapier3d::math::IVector;
// ★ 2026-09-05 typedef 精度切换：DVec3 = prec::DVec3（f64 模式=glam DVec3；f32 模式=f32 Vector）
pub type DVec3 = crate::prec::DVec3;

// ★ 2026-09-05 f32/f64 合并：feature 选择精度包（prec-f32 默认 / prec-f64），
//  统一 `use rapier3d::...`（extern crate as rapier3d 映射两种包名）。
#[cfg(feature = "prec-f64")]
extern crate rapier3d_f64 as rapier3d;
#[cfg(not(feature = "prec-f64"))]
extern crate rapier3d_f32 as rapier3d;


use jni::objects::{JClass, JDoubleArray, JIntArray, JString};
use jni::sys::{jboolean, jdouble, jint, jlong};
use jni::{JNIEnv, JavaVM};
use std::collections::HashMap;
use std::io::ErrorKind::BrokenPipe;
use std::io::{self, Write};
use std::sync::atomic::Ordering;
use std::sync::{Arc, OnceLock, RwLock};

use crate::scene::ShapeCacheEntry;
use fern::colors::{Color, ColoredLevelConfig};
use log::info;

use crate::buoyancy::compute_buoyancy;
use crate::collider::{LevelCollider, update_collider_aabb};
use crate::dispatcher::SableDispatcher;
use crate::event_handler::SableEventHandler;
use crate::groups::LEVEL_GROUP;
use crate::joints::SableJointSet;
use crate::rope::RopeMap;
use crate::scene::{
    ChunkAccess, ChunkMap, SableManifoldInfoMap, SableSceneData, SimulationSceneData,
    pack_section_pos,
};
use crate::voxel_collider::VoxelColliderMap;
use hooks::SablePhysicsHooks;
use crate::prec::Real;
use marten::level::VoxelPhysicsState::Interior;
use marten::level::{
    ALL_VOXEL_PHYSICS_STATES, BlockState, CHUNK_SHIFT, ChunkSection, OCTREE_CHUNK_SHIFT,
    OCTREE_CHUNK_SIZE, OctreeChunkSection, VoxelPhysicsState,
};
use marten::octree::SubLevelOctree;
use rapier3d::parry::query::{DefaultQueryDispatcher, QueryDispatcher};
use rapier3d::prelude::*;
use scene::{LevelColliderID, PhysicsScene, ReportedCollisionBuffer};

// ★ 2026-09-05 【日志节流（用户：打印过于频繁导致卡顿）】时间基节流器：
//   所有 [sabledbg] 打点共享同一全局开关，且限制每条至少 ms_interval 毫秒只打印一次
//   （替代原先「静态原子计数器 n<32||n%64」——碰撞发生后每步 200 次也要打一行，
//   长时间运行持续刷屏卡顿）。内部用 `Instant` + 各 key 独立 last 时间戳。
static DBG_PROFILE_ENABLED: std::sync::atomic::AtomicBool =
    std::sync::atomic::AtomicBool::new(true);   // ★ 临时 true 调穿透；稳定后应置 false
// 含时间戳节流：key → (last_ms, seq)。用 RwLock<HashMap<&'static str, u64>> 懒初始化。
static DBG_THROTTLE: std::sync::OnceLock<std::sync::RwLock<std::collections::HashMap<&'static str, u64>>> =
    std::sync::OnceLock::new();

/// 是否输出 [sabledbg]（默认 false；Java 侧可通过 JNI 开启，或环境变量
///  SABLE_DBG=1 使能）。关闭时所有打点 log::info! 分支直接跳过（零开销）。
pub fn sabledbg_enabled() -> bool {
    if !DBG_PROFILE_ENABLED.load(Ordering::Relaxed) {
        // 懒读环境变量一次（避免每次 check 都读 env）
        static INIT: std::sync::Once = std::sync::Once::new();
        INIT.call_once(|| {
            if let Ok(v) = std::env::var("SABLE_DBG") {
                DBG_PROFILE_ENABLED.store(v == "1" || v.eq_ignore_ascii_case("true"),
                    Ordering::Relaxed);
            }
        });
    }
    DBG_PROFILE_ENABLED.load(Ordering::Relaxed)
}

/// 时间基节流：同 key 距上次打印 >= min_interval_ms 才放行（并返回 true）。
/// 多线程安全（RwLock；碰撞回调主线程单线程调用，竞争极少）。
pub fn sabledbg_gate(key: &'static str, min_interval_ms: u64) -> bool {
    if !sabledbg_enabled() {
        return false;
    }
    static EPOCH: std::sync::OnceLock<std::time::Instant> = std::sync::OnceLock::new();
    let epoch = EPOCH.get_or_init(std::time::Instant::now);
    let now_ms = epoch.elapsed().as_millis() as u64;
    let map = DBG_THROTTLE.get_or_init(|| std::sync::RwLock::new(std::collections::HashMap::new()));
    if let Ok(mut g) = map.write() {
        let last = g.get(key).copied().unwrap_or(0);
        if now_ms.saturating_sub(last) >= min_interval_ms {
            g.insert(key, now_ms);
            true
        } else {
            false
        }
    } else {
        false
    }
}

/// 统一打点入口：只有 [sabledbg] 使能且满足节流才 log::info!
#[macro_export]
macro_rules! sabledbg {
    ($key:expr, $interval_ms:expr, $($arg:tt)*) => {{
        if $crate::sabledbg_gate($key, $interval_ms) {
            log::info!($($arg)*);
        }
    }};
}

#[derive(Debug)]
pub struct ActiveLevelColliderInfo {
    pub collider: ColliderHandle,
    pub static_mount: Option<RigidBodyHandle>,
    pub fake_velocities: Option<RigidBodyVelocity<Real>>,
    pub local_bounds_min: Option<IVec3>,
    pub local_bounds_max: Option<IVec3>,
    pub center_of_mass: Option<DVec3>,
    pub octree: Option<SubLevelOctree>,
    pub chunk_map: Option<ChunkMap>,
}

impl ChunkAccess for ActiveLevelColliderInfo {
    fn get_chunk_mut(&mut self, x: i32, y: i32, z: i32) -> Option<&mut ChunkSection> {
        self.chunk_map
            .as_mut()
            .unwrap()
            .get_mut(&pack_section_pos(x, y, z))
    }

    fn get_chunk(&self, x: i32, y: i32, z: i32) -> Option<&ChunkSection> {
        self.chunk_map
            .as_ref()
            .unwrap()
            .get(&pack_section_pos(x, y, z))
    }
}

impl ActiveLevelColliderInfo {
    /// Creates a new handle for a sable object with rigidbody and collider handles
    #[must_use]
    pub fn new(collider: ColliderHandle) -> Self {
        Self {
            collider,
            static_mount: None,
            fake_velocities: None,
            chunk_map: None,
            local_bounds_min: None,
            local_bounds_max: None,
            center_of_mass: None,
            octree: None,
        }
    }

    pub fn has_own_chunks(&self) -> bool {
        self.chunk_map.is_some()
    }

    /// Sets the local bounds for the object
    pub fn set_local_bounds(
        &mut self,
        min: IVec3,
        max: IVec3,
        _level_chunks: &ChunkMap,
        collider_map: &VoxelColliderMap,
    ) {
        // ★ 2026-09-03 穿透+崩溃根因修复：local bounds 必须【对齐到 section 边界】
        //   （min 下取整到 16 对齐节起点、max 上取整到节终点）。addChunk 的块按
        //   section（每 16 格一区块）上传，octree 局部坐标 = 块 - min：若 min 非 16
        //   对齐（如 1500054）→ 同一 section 内靠前的块局部坐标为【负】/越出 octree
        //   范围 → 旧 insert_chunk 的 range guard 全跳过 → 结构 octree 空
        //   （sableEmpty=true → find_collision_pairs 0 pair → 穿透）；或 rebuild
        //   无界写 → SubLevelOctree.insert 越界 → native panic 崩溃。
        let min = IVec3::new(min.x & !15, min.y & !15, min.z & !15);
        let max = IVec3::new(max.x | 15, max.y | 15, max.z | 15);
        if Some(min) != self.local_bounds_min || Some(max) != self.local_bounds_max {
            self.local_bounds_min = Some(min);
            self.local_bounds_max = Some(max);
            // ★ 2026-09-03 octree 只从 own chunk_map 重建（多 body 场景 must own 独占）
            self.rebuild_octree_from_own_chunks(collider_map);
        }
    }

    /// ★ 2026-09-03 octree【只从本 body 的 own chunk_map】重建。
    ///   原实现 has_own_chunks=false 时回退读 scene 共享的 main_level_chunks
    ///   （addChunk 无条件把每个 body 的块都塞进去）→ 本 body 的 octree 混入
    ///   【别的 body】的块（结构块污染陪体 octree；陪体块污染结构 octree）
    ///   → find_collision_pairs 在这些位置出 pair，但本 body 的 own chunk_map
    ///   那里无块(block_id=0) → b1/b2=全部 pair → 0 manifold → 结构穿透。
    ///   本方法独立抽取：addChunk 更新 chunk_map 后也调用 → octree 恒与 chunk_map 一致。
    fn rebuild_octree_from_own_chunks(&mut self, collider_map: &VoxelColliderMap) {
        let (Some(min), Some(max)) = (self.local_bounds_min, self.local_bounds_max) else {
            return;
        };
        let max_axis = (max - min).max_element() as u32 + 1;
        let smallest_pow_2_above = max_axis.next_power_of_two();
        let chunk_min = min >> CHUNK_SHIFT;
        let chunk_max = max >> CHUNK_SHIFT;
        self.octree = Some(SubLevelOctree::new(
            smallest_pow_2_above.trailing_zeros() as i32,
        ));

        let own_chunks = self.chunk_map.as_ref();
        let octree_size = 1i32 << self.octree.as_ref().unwrap().log_size;
        for cx in chunk_min.x..=chunk_max.x {
            for cy in chunk_min.y..=chunk_max.y {
                for cz in chunk_min.z..=chunk_max.z {
                    let chunk = own_chunks.and_then(|m| {
                        m.get(&pack_section_pos(cx as i32, cy as i32, cz as i32))
                    });
                    if let Some(chunk_section) = chunk {
                        for x in 0..16 {
                            for y in 0..16 {
                                for z in 0..16 {
                                    let block_owned = chunk_section.get_block(x, y, z);
                                    if block_owned.1 == VoxelPhysicsState::Empty {
                                        continue;
                                    }
                                    let lx = ((x as i64 + ((cx as i64) << CHUNK_SHIFT)) - min.x as i64) as i32;
                                    let ly = ((y as i64 + ((cy as i64) << CHUNK_SHIFT)) - min.y as i64) as i32;
                                    let lz = ((z as i64 + ((cz as i64) << CHUNK_SHIFT)) - min.z as i64) as i32;
                                    // 防御：越界（正常情况下 min 已 16 对齐，不会触发）→ 跳过而非写坏 octree
                                    if lx < 0 || ly < 0 || lz < 0
                                        || lx >= octree_size || ly >= octree_size || lz >= octree_size
                                    {
                                        continue;
                                    }
                                    insert_block_octree(
                                        collider_map,
                                        self.octree.as_mut().unwrap(),
                                        &block_owned,
                                        false,
                                        lx, ly, lz,
                                    );
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fn insert_chunk(
        &mut self,
        chunk_section: &ChunkSection,
        cx: i32,
        cy: i32,
        cz: i32,
        collider_map: &VoxelColliderMap,
    ) {
        for x in 0..16 {
            for y in 0..16 {
                for z in 0..16 {
                    self.insert_block(
                        x + (cx << CHUNK_SHIFT),
                        y + (cy << CHUNK_SHIFT),
                        z + (cz << CHUNK_SHIFT),
                        &chunk_section.get_block(x, y, z),
                        false,
                        collider_map,
                    );
                }
            }
        }
    }

    fn insert_block(
        &mut self,
        x: i32,
        y: i32,
        z: i32,
        state: &BlockState,
        remove: bool,
        collider_map: &VoxelColliderMap,
    ) {
        let local_min = self.local_bounds_min.unwrap();
        let lx = (x as i64 - local_min.x as i64) as i32;
        let ly = (y as i64 - local_min.y as i64) as i32;
        let lz = (z as i64 - local_min.z as i64) as i32;

        // ★ 2026-09-03 穿透根因修复：SubLevelOctree::insert 无边界检查——越界/负
        //   局部坐标会错位/镜像插入（幻影块）→ octree 找到的 block 在 chunk_map 中
        //   为空(block_id=0) → 0 manifold → 结构穿透。Octree 范围 = [0, 2^log_size)。
        //   Java 已把陪体 bounds 扩到扫面盒使世界 section 在范围内；此处兜底：越界
        //   块不属于本 body，直接跳过，防幻影/索引越界。
        let Some(octree) = &mut self.octree else {
            panic!("No octree!");
        };
        let octree_size = 1i32 << octree.log_size;
        if lx < 0 || ly < 0 || lz < 0
            || lx >= octree_size || ly >= octree_size || lz >= octree_size
        {
            // ★ 2026-09-03 诊断：range guard 跳过（若结构自身块也被跳过 → octree 空 → 穿透）
            crate::sabledbg!("insertBLK-skip", 1000,
                "[sabledbg] insertBLK-SKIP lm=({},{},{}) lc=({},{},{}) size={}",
                local_min.x, local_min.y, local_min.z, lx, ly, lz, octree_size
            );
            return;
        }
        insert_block_octree(collider_map, octree, state, remove, lx, ly, lz);
    }

    fn contains(&self, x: i32, y: i32, z: i32) -> bool {
        if self.local_bounds_min.is_none() || self.local_bounds_max.is_none() {
            return false;
        }

        let local_min = self.local_bounds_min.unwrap();
        let local_max = self.local_bounds_max.unwrap();

        i64::from(x) >= local_min.x as i64
            && i64::from(x) <= local_max.x as i64
            && i64::from(y) >= local_min.y as i64
            && i64::from(y) <= local_max.y as i64
            && i64::from(z) >= local_min.z as i64
            && i64::from(z) <= local_max.z as i64
    }
}

/// Global physics engine state shared across all scenes.
pub struct PhysicsState {
    /// Default integration-parameter template. Copied into each new scene on
    /// `initialize`; the config JNI calls re-apply it to all live scenes.
    /// ★ 2026-09-02 从全局单例迁走：step 只读写 per-scene 参数，不再持全局锁 →
    ///   多 scene 可并行 step（progress：多个 scene 同时计算）。
    default_integration_parameters: IntegrationParameters,

    /// An array of i32 IDs -> block collider entries
    voxel_collider_map: VoxelColliderMap,

    /// Live scene handles (Arc::into_raw pointers) for batched config application.
    /// Mutated only under the global write lock (initialize/config/dispose mutually
    /// exclusive) so iterating a scene pointer here is always valid.
    scenes: Vec<usize>,
}

/// A collision to report to the Java side.
#[derive(Debug, Clone)]
pub struct ReportedCollision {
    body_a: Option<LevelColliderID>,
    body_b: Option<LevelColliderID>,
    local_point_a: DVec3,
    local_point_b: DVec3,
    local_normal_a: DVec3,
    local_normal_b: DVec3,
    force_amount: f64,
}

pub static PHYSICS_STATE: OnceLock<RwLock<PhysicsState>> = OnceLock::new();

pub fn with_handle<F, R>(handle: jlong, f: F) -> R
where
    F: FnOnce(&PhysicsScene) -> R,
{
    assert!(handle != 0, "null scene handle");
    unsafe { f(&*(handle as *const PhysicsScene)) }
}

#[inline(always)]
pub fn get_physics_state() -> std::sync::RwLockReadGuard<'static, PhysicsState> {
    PHYSICS_STATE
        .get()
        .expect("No physics state!")
        .read()
        .unwrap()
}

#[inline(always)]
pub fn get_physics_state_mut() -> std::sync::RwLockWriteGuard<'static, PhysicsState> {
    PHYSICS_STATE
        .get()
        .expect("No physics state!")
        .write()
        .unwrap()
}

#[inline(always)]
pub fn get_rigid_body_mut<'a>(
    sim: &'a mut SimulationSceneData,
    sable_data: &SableSceneData,
    id: LevelColliderID,
) -> &'a mut RigidBody {
    let handle = sable_data
        .rigid_bodies
        .get(&id)
        .expect("No rigid body for id");
    &mut sim.rigid_body_set[*handle]
}

#[inline(always)]
pub fn get_rigid_body<'a>(
    sim: &'a SimulationSceneData,
    sable_data: &SableSceneData,
    id: LevelColliderID,
) -> &'a RigidBody {
    let handle = sable_data
        .rigid_bodies
        .get(&id)
        .expect("No rigid body for id");
    &sim.rigid_body_set[*handle]
}

static RUST_LOG_FILE: std::sync::Mutex<Option<std::fs::File>> = std::sync::Mutex::new(None);

struct LogWriter;

impl Write for LogWriter {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        let mut stdout = io::stdout().lock();
        match stdout.write(buf) {
            Err(error) => {
                if error.kind() != BrokenPipe {
                    return Err(error);
                }
            }
            Ok(_) => {}
        }

        // ★ 2026-09-03 同时落盘 fixed 文件（logs/sable_rust.log，相对进程 cwd=neoforge/run）
        //   避免 stdout 未被捕获时 Rust 侧打点全部丢失（wvw-summary 定位必需）。
        if let Ok(mut guard) = RUST_LOG_FILE.lock() {
            if guard.is_none() {
                let opened = ["logs/sable_rust.log", "sable_rust.log"]
                    .iter()
                    .find_map(|p| {
                        std::fs::OpenOptions::new()
                            .create(true)
                            .append(true)
                            .open(p)
                            .ok()
                    });
                *guard = opened;
            }
            if let Some(f) = guard.as_mut() {
                let _ = f.write_all(buf);
            }
        }

        Ok(buf.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        let mut stdout = io::stdout().lock();
        let _ = stdout.flush();

        if let Ok(mut guard) = RUST_LOG_FILE.lock() {
            if let Some(f) = guard.as_mut() {
                let _ = f.flush();
            }
        }

        Ok(())
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_initialize<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    x: jdouble,
    y: jdouble,
    z: jdouble,
    universal_drag: jdouble,
) -> jlong {
    PHYSICS_STATE.get_or_init(|| {
        let colors = ColoredLevelConfig::new()
            .info(Color::Green)
            .error(Color::Red)
            .debug(Color::Blue);

        let _ = fern::Dispatch::new()
            .format(move |out, message, record| {
                out.finish(format_args!(
                    "[{}] [{}] ({}) {}",
                    humantime::format_rfc3339(std::time::SystemTime::now()),
                    colors.color(record.level()),
                    record.target(),
                    message
                ))
            })
            .level(log::LevelFilter::Info)
            .level_for("jni", log::LevelFilter::Error)
            .chain(fern::Output::writer(Box::new(LogWriter), "\n"))
            .apply();

        RwLock::new(PhysicsState {
            default_integration_parameters: IntegrationParameters {
                dt: 1.0 / 20.0,

                // ★ 2026-09-03 用户要求取消兜底补丁：恢复温和/官方求解参数
                //   （去掉强弹开与巨大 prediction——那些曾把结构猛弹到巨大坐标）。
                max_ccd_substeps: 3,
                normalized_prediction_distance: 0.05,

                contact_softness: SpringCoefficients {
                    natural_frequency: 40.0,
                    damping_ratio: 4.0,
                },

                normalized_max_corrective_velocity: 60.0,
                normalized_allowed_linear_error: 0.002,

                ..IntegrationParameters::default()
            },
            voxel_collider_map: VoxelColliderMap::new(),
            scenes: Vec::new(),
        })
    });

    let ground = RigidBodyBuilder::fixed();

    let collider = ColliderBuilder::new(SharedShape::new(LevelCollider::new(None, true)))
        .collision_groups(LEVEL_GROUP)
        .build();

    let sable_data = Arc::new(RwLock::new(SableSceneData {
        main_level_chunks: HashMap::<i64, ChunkSection>::new(),
        octree_chunks: HashMap::<i64, OctreeChunkSection>::new(),
        joint_set: SableJointSet::new(),
        rope_map: RopeMap::default(),
        level_colliders: HashMap::<LevelColliderID, ActiveLevelColliderInfo>::new(),
        rigid_bodies: HashMap::<LevelColliderID, RigidBodyHandle>::new(),
        // 2026-09-04 shape cache: empty at start; limit default unlimited(-1).
        shape_cache: HashMap::<LevelColliderID, ShapeCacheEntry>::new(),
        shape_cache_limit: std::sync::atomic::AtomicI64::new(-1),
        shape_cache_min_evict: std::sync::atomic::AtomicI64::new(3),
        shape_cache_shrink_pending: std::sync::atomic::AtomicBool::new(false),
    }));
    let manifold_info_map = Arc::new(SableManifoldInfoMap::default());
    let reported_collisions = Arc::new(ReportedCollisionBuffer::new());
    let current_step_vm = Some(Arc::new(unsafe {
        JavaVM::from_raw(env.get_java_vm().unwrap().get_java_vm_pointer()).unwrap()
    }));

    let dispatcher = SableDispatcher {
        sable_data: Arc::clone(&sable_data),
        manifold_info_map: Arc::clone(&manifold_info_map),
    };

    // ★ 2026-09-02 per-scene 集成参数从全局默认模板复制（config JNI 后改全部已建场景）。
    let default_params = crate::get_physics_state().default_integration_parameters.clone();

    let mut scene = PhysicsScene {
        sim_data: RwLock::new(SimulationSceneData {
            pipeline: PhysicsPipeline::new(),
            rigid_body_set: RigidBodySet::new(),
            collider_set: ColliderSet::new(),
            island_manager: IslandManager::new(),
            broad_phase: DefaultBroadPhase::new(),
            narrow_phase: NarrowPhase::with_query_dispatcher(
                dispatcher.chain(DefaultQueryDispatcher),
            ),
            impulse_joint_set: ImpulseJointSet::new(),
            multibody_joint_set: MultibodyJointSet::new(),
            ccd_solver: CCDSolver::new(),
            physics_hooks: SablePhysicsHooks {
                sable_data: Arc::clone(&sable_data),
                manifold_info_map: Arc::clone(&manifold_info_map),
                current_step_vm: current_step_vm.clone(),
            },
            event_handler: SableEventHandler {
                reported_collisions: Arc::clone(&reported_collisions),
            },
        }),
        sable_data,
        ground_handle: None,
        reported_collisions,
        current_step_vm,
        gravity: Vec3::new(x as Real, y as Real, z as Real),
        universal_drag: universal_drag as Real,
        manifold_info_map,
        integration_parameters: RwLock::new(default_params),
    };

    {
        let mut sim_data = scene.sim_data.write().unwrap();
        sim_data.collider_set.insert(collider);

        scene.ground_handle = Some(sim_data.rigid_body_set.insert(ground));
    }

    info!("Rapier scene initialized");

    let handle = Arc::into_raw(Arc::new(scene)) as jlong;
    // ★ 2026-09-02 无节流版本锚点（确认 dll 是否真的加载、进程是否重启）
    log::info!("[sabledbg] dll initialize ok handle={}", handle);    // ★ 2026-09-02 登记全局 scene 注册表（config 批量应用遍历用；dispose 移除）。
    crate::get_physics_state_mut().scenes.push(handle as usize);
    handle
}

/// setShapeCacheLimit(handle, limit, min_evict): set max cached shape companion bodies.
/// limit >= 0: max count. -1: unlimited. 0: cache disabled (all evicted).
/// min_evict: minimum entries removed per eviction batch (e.g. 3; default 3).
/// Shrinking (new < old) sets shrink_pending so the NEXT ensure evicts down to
/// the new limit. Expanding or equal never evicts.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_setShapeCacheLimit<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    limit: jlong,
    min_evict: jlong,
) {
    with_handle(handle, |scene| {
        let old = {
            let sable_data = scene.sable_data.read().unwrap();
            if min_evict > 0 {
                sable_data
                    .shape_cache_min_evict
                    .store(min_evict as i64, Ordering::Relaxed);
            }
            sable_data
                .shape_cache_limit
                .swap(limit as i64, Ordering::Relaxed)
        };
        if limit >= 0 && (old < 0 || limit < old) {
            // Shrunk (or first finite limit): mark + evict down to limit NOW.
            scene.sable_data.write().unwrap().shape_cache_shrink_pending
                .store(true, Ordering::Relaxed);
            // Drop read guard (inside scope) then evict (takes its own locks).
            crate::shapes::evict_shape_cache(scene);
        }
    });
}

/// getShapeCacheLimit(handle): current limit (i64). -1 = unlimited.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_getShapeCacheLimit<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jlong {
    let mut out: jlong = -1;
    with_handle(handle, |scene| {
        let sable_data = scene.sable_data.read().unwrap();
        out = sable_data
            .shape_cache_limit
            .load(Ordering::Relaxed) as jlong;
    });
    out
}

/// getShapeCacheMinEvict(handle): current min-evict batch size (i64).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_getShapeCacheMinEvict<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jlong {
    let mut out: jlong = 3;
    with_handle(handle, |scene| {
        let sable_data = scene.sable_data.read().unwrap();
        out = sable_data
            .shape_cache_min_evict
            .load(Ordering::Relaxed) as jlong;
    });
    out
}

/// evictShapeCache(handle): force one eviction pass down to current limit
/// (also used internally after shrinking). Returns number removed.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_evictShapeCache<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jlong {
    let mut removed: jlong = 0;
    with_handle(handle, |scene| {
        removed = crate::shapes::evict_shape_cache(scene) as jlong;
    });
    removed
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_dispose<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    if handle != 0 {
        // ★ 2026-09-02 从全局注册表移除（initialize/config/dispose 均由全局写锁互斥 → 指针安全）
        crate::get_physics_state_mut().scenes.retain(|h| *h != handle as usize);
        unsafe {
            drop(Arc::from_raw(handle as *const PhysicsScene));
        }
    }
}

// ================= 2026-09-05 场景桶（bucketed coordinates）支持 =================
// recenterScene(handle, dx, dy, dz): 平移整个物理场景内所有刚体 body 位置 (dx,dy,dz)
// （世界坐标单位）。collider 挂 body 自动跟随；唤醒所有 body；不触碰 sable_data 的
// 区块/体素表（桶只作用于 shape 模式的结构/陪体 body；voxel 路径不受影响，其
// 大坐标仍由 Java far 换算兜底）。用于结构远离当前桶原点时"动态重居中"，保持
// f32 物理永远在原点附近小数值范围（彻底免疫距离失真）。

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_recenterScene<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    dx: jdouble,
    dy: jdouble,
    dz: jdouble,
) -> jint {
    if handle == 0 || (dx == 0.0 && dy == 0.0 && dz == 0.0) {
        return 0;
    }
    let offset = Vec3::new(dx as Real, dy as Real, dz as Real);
    let mut count: usize = 0;
    with_handle(handle, |scene| {
        let mut sim_data = scene.sim_data.write().unwrap();
        let sim_data = &mut *sim_data;
        sim_data.rigid_body_set.iter_mut().for_each(|(_, rb)| {
            let mut pos = *rb.position();
            // ★ f32 版 rapier math::Vec3 无 .vector 字段（f64 版有）：统一用 +=（两版支持）
            pos.translation += offset;
            rb.set_position(pos, true);
            rb.wake_up(true);
            count += 1;
        });
    });
    count as jint
}

/// Extracts a message from a caught panic payload
fn panic_message(payload: &Box<dyn std::any::Any + Send>) -> String {
    if let Some(s) = payload.downcast_ref::<&str>() {
        s.to_string()
    } else if let Some(s) = payload.downcast_ref::<String>() {
        s.clone()
    } else {
        "unknown panic".to_string()
    }
}

/// Catches a panic and throws a JVM RuntimeException with the panic message
fn throw_on_panic(env: &mut JNIEnv, result: Result<(), Box<dyn std::any::Any + Send>>) {
    if let Err(payload) = result {
        let msg = format!("Rapier native panic: {}", panic_message(&payload));
        let _ = env.throw_new("java/lang/RuntimeException", &msg);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_tick<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    _time_step: jdouble,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        with_handle(handle, |scene| {
            rope::tick(scene);
            joints::tick(scene);
            compute_buoyancy(scene);
        });
    }));

    throw_on_panic(&mut env, result);
}

/// Steps physics
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_step<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    time_step: jdouble,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        with_handle(handle, |scene| {
            rope::tick(scene);
            joints::tick(scene);

            scene.manifold_info_map.clear();

            let gravity = scene.gravity;
            let mut sim = scene.sim_data.write().unwrap();
            let sim = &mut *sim;

            // ★ 2026-09-02 per-scene 参数：不碰全局锁（多 scene 并行 step 的核心）。
            //   dt 写入自己 scene 的 RwLock，其他 scene 的 step 无竞争。
            scene.integration_parameters.write().unwrap().dt = time_step as Real;
            let params = scene.integration_parameters.read().unwrap();

            sim.pipeline.step(
                gravity,
                &*params,
                &mut sim.island_manager,
                &mut sim.broad_phase,
                &mut sim.narrow_phase,
                &mut sim.rigid_body_set,
                &mut sim.collider_set,
                &mut sim.impulse_joint_set,
                &mut sim.multibody_joint_set,
                &mut sim.ccd_solver,
                &sim.physics_hooks,
                &sim.event_handler,
            );
        });
    }));

    throw_on_panic(&mut env, result);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_getPose<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
    store: JDoubleArray<'local>,
) {
    let arr: [jdouble; 7] = with_handle(handle, |scene| {
        let sable_data = scene.sable_data.read().unwrap();
        let sim_data = scene.sim_data.read().unwrap();

        // ★ 2026-09-06 防御：body id 不在 rigid_bodies（重建/删除窗口期被并发查询）→
        //   返回零数组（Java 侧 pOk 判定 0 跳过），而非 `[]` Index panic（中止进程）。
        let Some(rb_handle) = sable_data.rigid_bodies.get(&(id as LevelColliderID)) else {
            return [0.0; 7];
        };
        let rb = &sim_data.rigid_body_set[*rb_handle];

        [
            rb.translation().x as jdouble,
            rb.translation().y as jdouble,
            rb.translation().z as jdouble,
            rb.rotation().x as jdouble,
            rb.rotation().y as jdouble,
            rb.rotation().z as jdouble,
            rb.rotation().w as jdouble,
        ]
    });
    env.set_double_array_region(&store, 0, &arr).unwrap();
}

/// ★ 2026-09-05 【批量位姿（用户：坐标更新按物理空间为单位，计算完成后一次拉取）】
/// 一次 native 调用返回场景内【全部】刚性体的位姿：扁平数组
///   [id0, x0,y0,z0, qx0,qy0,qz0,qw0, id1, ...]
/// 返回大小 = body_count × 8（每体 1 个 id + 7 个位姿分量）。
/// Java 侧按空间分组后逐体更新数据表（投影坐标）。
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_getPoseBatch<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    store: JDoubleArray<'local>,
    out_count: JIntArray<'local>,
) {
    with_handle(handle, |scene| {
        let sable_data = scene.sable_data.read().unwrap();
        let sim_data = scene.sim_data.read().unwrap();

        // 收集全部 rigid body 位姿（顺序可能与 Java 插入顺序不同 → 带 id）
        let mut items: Vec<(LevelColliderID, [f64; 7])> = Vec::new();
        for (id, rb_handle) in &sable_data.rigid_bodies {
            let rb = &sim_data.rigid_body_set[*rb_handle];
            items.push((
                *id,
                [
                    rb.translation().x as f64,
                    rb.translation().y as f64,
                    rb.translation().z as f64,
                    rb.rotation().x as f64,
                    rb.rotation().y as f64,
                    rb.rotation().z as f64,
                    rb.rotation().w as f64,
                ],
            ));
        }

        let mut out: Vec<jdouble> = Vec::with_capacity(items.len() * 8);
        for (id, p) in items {
            out.push(id as jdouble);
            out.extend_from_slice(&p);
        }
        // ★ 2026-09-05 安全：store 可能小于 out（Java 预分配不足）→ 截断到 store 容量
        //   （只返回能容纳的前若干体；Java 侧以 out_count 为准）。
        let cap = env.get_array_length(&store).unwrap_or(0) as usize;
        if out.len() > cap {
            out.truncate(cap);
        }
        env.set_double_array_region(&store, 0, &out).unwrap();
        let cnt = [out.len() as jint / 8];
        env.set_int_array_region(&out_count, 0, &cnt).unwrap();
    })
}

/// ★ 2026-09-04 【AABB 相交查询（物理空间重叠检测用）】
/// 在指定场景中查询：哪些结构 body 的世界 AABB 与给定 AABB（查询盒，世界坐标）相交。
/// 用于【物理空间合并/拆分】的重叠判定（用户定案：走 Rust 物理碰撞检测重叠，可靠）。
/// 实现：遍历 scene.collider_set 全部 collider，compute_aabb 求世界 AABB，
/// 与查询盒相交 → 取其 parent rigid body → 反查 rigid_bodies(id→handle) 得 LevelColliderID。
/// ⚠ 只统计结构 body（rigid_bodies 映射内的）；ground/陪体（无 id 映射）不返回。
/// ⚠ 跨空间：每个物理空间是独立 scene，本查询只查单个 scene——Java 侧把另一空间的
///   成员世界 AABB 转换到本空间局部坐标后查询（重叠检测在统一坐标下做）。
/// @param handle scene handle
/// @param minX..maxZ 查询盒（【世界坐标】；调用方需转换到本场景坐标系）
/// @param out_ids 预分配 int[]（Java 传 64/128 容量），写入相交的 LevelColliderID
/// @return 相交 body 数（≤ out_ids 容量；超出截断）
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_queryAabbIntersecting<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    minx: jdouble,
    miny: jdouble,
    minz: jdouble,
    maxx: jdouble,
    maxy: jdouble,
    maxz: jdouble,
    out_ids: JIntArray<'local>,
) -> jint {
    let ids: Vec<jint> = with_handle(handle, |scene| {
        let sable_data = scene.sable_data.read().unwrap();
        let sim_data = scene.sim_data.read().unwrap();
        // 反查：rigid body handle → 自定义 id（rigid_bodies: id→handle）
        let mut body_to_id: HashMap<RigidBodyHandle, LevelColliderID> = HashMap::new();
        for (id, rb_handle) in &sable_data.rigid_bodies {
            body_to_id.insert(*rb_handle, *id);
        }
        let mut found: Vec<jint> = Vec::new();
        for (_, collider) in sim_data.collider_set.iter() {
            let aabb = collider.compute_aabb();
            // 手动 AABB 相交判定（不依赖 parry intersects API，避免版本差异）
            if aabb.mins.x > maxx as Real || aabb.maxs.x < minx as Real
                || aabb.mins.y > maxy as Real || aabb.maxs.y < miny as Real
                || aabb.mins.z > maxz as Real || aabb.maxs.z < minz as Real {
                continue;
            }
            // collider → parent rigid body → 自定义 id
            if let Some(parent) = collider.parent() {
                if let Some(&id) = body_to_id.get(&parent) {
                    let id_i = id as jint;
                    if !found.contains(&id_i) {
                        found.push(id_i);
                    }
                }
            }
        }
        found
    });
    let cap = env.get_array_length(&out_ids).unwrap_or(0) as usize;
    let n = ids.len().min(cap);
    if n > 0 {
        let _ = env.set_int_array_region(&out_ids, 0, &ids[..n]);
    }
    n as jint
}

/// ★ 2026-09-05 【陪体扫描区差异同步（用户：差异计算完全交给 Rust）】
/// 传入陪体(id)的【最新完整扫描 section 集】，Rust 对比自身维护的旧 own chunk_map，
/// 只对差异部分做增删改（新增 section / 删除多余 / 覆盖内容变化），再重建 octree 保证
/// 与 chunk_map 一致。替代 Java 侧全量 removeSubLevel + addChunk 重建。
///
/// 语义：Java 每次把该陪体最新的全部扫描 section（扁平数组）交过来，Rust 内部 diff。
/// @param handle scene handle
/// @param id 陪体 body 自定义 id（level_colliders 键）
/// @param section_count 本次 section 数
/// @param sec_keys 扁平 [x,y,z] * section_count（局部 section 坐标）
/// @param chunk_data 扁平 int[4096] * section_count（xzy 序，x 最快）
/// @param minX..maxZ 最新局部 bounds（块坐标；diff 后 setLocalBounds 用）
/// @return 变化 section 数（0 = 无变化，Java 可跳过）
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_syncCompanionChunks<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
    section_count: jint,
    sec_keys: JIntArray<'local>,
    chunk_data: JIntArray<'local>,
    minx: jint,
    miny: jint,
    minz: jint,
    maxx: jint,
    maxy: jint,
    maxz: jint,
) -> jint {
    let sc = section_count.max(0) as usize;
    if sc == 0 {
        return 0;
    }
    // 读入 keys（3 * sc）与 chunk data（4096 * sc）
    let mut keys = vec![0i32; sc * 3];
    let _ = env.get_int_array_region(&sec_keys, 0, &mut keys);
    let mut data = vec![0i32; sc * 4096];
    let _ = env.get_int_array_region(&chunk_data, 0, &mut data);

    with_handle(handle, |scene| {
        let physics_state = get_physics_state();
        let collider_map = &physics_state.voxel_collider_map;
        let mut sable_data = scene.sable_data.write().unwrap();
        let Some(body) = sable_data
            .level_colliders
            .get_mut(&(id as LevelColliderID))
        else {
            log::warn!("[sabledbg] syncCompanionChunks skip id={} not registered", id);
            return 0;
        };

        // 构建新 map（pack_section_pos(x,y,z) → ChunkSection）
        let mut new_map: HashMap<i64, ChunkSection> = HashMap::new();
        for s in 0..sc {
            let x = keys[s * 3];
            let y = keys[s * 3 + 1];
            let z = keys[s * 3 + 2];
            let base = s * 4096;
            let mut blocks = Vec::with_capacity(4096);
            for b in 0..4096 {
                let block = data[base + b];
                let block_collider_id = (block >> 16) as u16;
                let voxel_state_id = (block & 0xFFFF) as u16;
                blocks.push((
                    block_collider_id as u32,
                    ALL_VOXEL_PHYSICS_STATES[voxel_state_id as usize],
                ));
            }
            new_map.insert(pack_section_pos(x, y, z), ChunkSection::new(blocks));
        }

        // own chunk_map 惰性建
        if body.chunk_map.is_none() {
            body.chunk_map = Some(ChunkMap::new());
        }
        let own = body.chunk_map.as_mut().unwrap();

        let mut changes = 0i32;
        // ① 新增 / 内容变化覆盖（新有旧无 → insert；新旧都有但不同 → replace）
        // ★ 2026-09-05 ChunkSection 无 PartialEq 无法比较 → 一律覆盖（弃置区体素路径；
        //   凹形组合 compound 已取代本方法）。deprecated 语义：diff=全部重传，正确但非最小。
        for (key, chunk) in &new_map {
            let changed = match own.get(key) {
                None => true,
                Some(_old) => true,
            };
            if changed {
                own.insert(*key, chunk.clone());
                changes += 1;
            }
        }
        // ② 删除多余（旧有新无 → remove）
        let old_keys: Vec<i64> = own.keys().cloned().collect();
        for k in old_keys {
            if !new_map.contains_key(&k) {
                own.remove(&k);
                changes += 1;
            }
        }
        // ★ 2026-09-05 借位修复：先取 own 计数并结束 own 的可变借用，再改 body 其它字段
        //   （NLL：sabledbg 末尾还引用 own.len() → 借用延伸到 bounds/rebuild → E0499）。
        let own_len = own.len();
        drop(own);

        // ③ 更新局部 bounds（对齐 16；与 setLocalBounds 语义一致）
        // ★ 2026-09-05 修复：f64 模式 IVec3=glam i64 → jint 需 as i64。
        body.local_bounds_min = Some(IVec3::new(
            (minx & !15) as i64,
            (miny & !15) as i64,
            (minz & !15) as i64,
        ));
        body.local_bounds_max = Some(IVec3::new(
            (maxx | 15) as i64,
            (maxy | 15) as i64,
            (maxz | 15) as i64,
        ));
        // ④ 从 own chunk_map 重建 octree（保证一致）
        body.rebuild_octree_from_own_chunks(collider_map);
        crate::sabledbg!("syncCompanionChunks", 2000,
            "[sabledbg] syncCompanion id={} sec={} changes={} own={} lb=({},{},{})..({},{},{})",
            id, sc, changes, own_len, minx, miny, minz, maxx, maxy, maxz);
        changes
    })
}

/// ★ 2026-09-06 【OBJ 导出（调试：每个物理空间一个 OBJ）】把指定 id 集合（结构+陪体）
/// 的全部体素按【世界坐标】导出 Wavefront OBJ 文本写入输出路径（String path）。
/// 遍历每个 id 的 own chunk_map 非空块 → 每方块一个 1x1 立方体（顶点/面 OBJ）。
/// 坐标 = body pose(translation/rotation) 旋转后的局部块坐标。
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_exportObj<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    ids: JIntArray<'local>,
    path: JString<'local>,
) {
    let path_str: String = env
        .get_string(&path)
        .map(|s| s.into())
        .unwrap_or_else(|_| "sable_export.obj".to_string());
    let len = env.get_array_length(&ids).unwrap_or(0) as usize;
    let mut id_buf = vec![0i32; len];
    env.get_int_array_region(&ids, 0, &mut id_buf).unwrap();
    let id_list: Vec<jint> = id_buf;

    with_handle(handle, |scene| {
        let sable_data = scene.sable_data.read().unwrap();
        let sim_data = scene.sim_data.read().unwrap();
        let mut obj = String::new();
        let mut vertices: Vec<[f64; 3]> = Vec::new();
        let mut faces: Vec<[u32; 4]> = Vec::new();

        // 每个 id：body pose + 该 body 的全部 collider → 世界坐标形状。
        // ★ 2026-09-06 【所有形状】不再只导出 chunk_map（voxel）：遍历该 body 的全部
        //   collider，按 shape 类型导出——voxel（LevelCollider/Custom）走 chunk_map 每格
        //   立方体；标准 shape（ball/capsule/cuboid/convex/trimesh）导出对应几何。
        for id in id_list {
            let lid = id as LevelColliderID;
            let Some(&body_handle) = sable_data.rigid_bodies.get(&lid) else {
                continue;
            };
            let body = &sim_data.rigid_body_set[body_handle];
            let t = body.translation();
            let rot = body.rotation();
            // 遍历该 body 的全部 collider
            let collider_handles: Vec<_> = body
                .colliders()
                .iter()
                .map(|ch| *ch)
                .collect();
            for ch in collider_handles {
                let collider = &sim_data.collider_set[ch];
                // collider 相对 body 的偏移（伴生 shape 可带局部偏移）
                let cpos = *collider.position();
                let shape = collider.shape();
                // downcast shape：是 LevelCollider（voxel）就导 chunk_map；否则标准 shape
                if let Some(lc) = shape.as_shape::<crate::collider::LevelCollider>() {
                    // voxel：遍历该 id 的 own chunk_map（若有）
                    if let Some(info) = sable_data.level_colliders.get(&lid) {
                        if let Some(chunks) = &info.chunk_map {
                            export_voxel_chunks(&mut vertices, &mut faces, t, cpos, *rot, chunks);
                        }
                    }
                    let _ = lc; // 借用避免未用告警（is_static/id 当前不参与几何）
                } else {
                    // 标准 shape：按 TypedShape 导出
                    export_typed_shape(&mut vertices, &mut faces, t, cpos, *rot, shape);
                }
            }
        }

        // 写 OBJ 文本
        obj.push_str("# Cryptand sable rapier OBJ export (world coords)\n");
        for v in &vertices {
            obj.push_str(&format!("v {:.6} {:.6} {:.6}\n", v[0], v[1], v[2]));
        }
        for f in &faces {
            obj.push_str(&format!("f {} {} {} {}\n", f[0] + 1, f[1] + 1, f[2] + 1, f[3] + 1));
        }
        if let Err(e) = std::fs::write(&path_str, obj.as_bytes()) {
            log::warn!("[sabledbg] exportObj write failed: {}", e);
        } else {
            log::info!(
                "[sabledbg] exportObj ok path={} verts={} faces={}",
                path_str,
                vertices.len(),
                faces.len()
            );
        }
    })
}

/// 导出 voxel 结构体/陪体的 chunk_map（每非空块一个 1x1 立方体；body 局部坐标随 pose）。
/// 复用原有每块立方体生成逻辑。
#[allow(clippy::too_many_arguments)]
fn export_voxel_chunks(
    vertices: &mut Vec<[f64; 3]>,
    faces: &mut Vec<[u32; 4]>,
    t: crate::Vec3,
    _cpos: crate::Pose3,
    rot: crate::Quat,
    chunks: &HashMap<i64, marten::level::ChunkSection>,
) {
    for (key, section) in chunks.iter() {
        // section 坐标解包（pack_section_pos 的逆：x=key>>42 & 4194303 等）
        let sx = ((key >> 42) & 4194303) as i32;
        let sz = ((key >> 20) & 4194303) as i32;
        let sy = ((key & 1048575) as i32);
        for bx in 0..16i32 {
            for by in 0..16i32 {
                for bz in 0..16i32 {
                    let block = section.get_block(bx, by, bz);
                    if block.0 == 0 {
                        continue;
                    }
                    // 局部块中心（相对空间原点）→ 相对 body 原点 = local - t；世界 = t + rot*(local-t)
                    // ★ 2026-09-05 修复：prec::DVec3 在 f64 模式映射到 glamx::DVec3（f32 固定）
                    //   ——必须直接用 crate::Vec3(=math::Vector，随精度)。
                    let local = crate::Vec3::new(
                        (sx * 16 + bx) as Real + 0.5,
                        (sy * 16 + by) as Real + 0.5,
                        (sz * 16 + bz) as Real + 0.5,
                    );
                    let world = rot * (local - t) + t;
                    let base = vertices.len() as u32;
                    let cx = world.x as f64;
                    let cy = world.y as f64;
                    let cz = world.z as f64;
                    let s = 0.5; // 半边长
                    for (dx, dy, dz) in [
                        (-s, -s, -s),
                        (s, -s, -s),
                        (s, s, -s),
                        (-s, s, -s),
                        (-s, -s, s),
                        (s, -s, s),
                        (s, s, s),
                        (-s, s, s),
                    ] {
                        vertices.push([cx + dx, cy + dy, cz + dz]);
                    }
                    for f in [
                        (0u32, 1u32, 2u32, 3u32),
                        (4u32, 5u32, 6u32, 7u32),
                        (0u32, 1u32, 5u32, 4u32),
                        (2u32, 3u32, 7u32, 6u32),
                        (0u32, 3u32, 7u32, 4u32),
                        (1u32, 2u32, 6u32, 5u32),
                    ] {
                        faces.push([base + f.0, base + f.1, base + f.2, base + f.3]);
                    }
                }
            }
        }
    }
}

/// 导出标准 rapier shape（球/胶囊/盒/凸包/三角网格）为 OBJ 几何；应用 body pose 变换。
#[allow(clippy::too_many_arguments)]
fn export_typed_shape(
    vertices: &mut Vec<[f64; 3]>,
    faces: &mut Vec<[u32; 4]>,
    t: crate::Vec3,
    cpos: crate::Pose3,
    rot: crate::Quat,
    shape: &dyn rapier3d::geometry::Shape,
) {
    use rapier3d::geometry::TypedShape;
    let cpos_v = cpos.translation;
    // world = t + bodyRot * (colliderPos + shapePoint)（shape 点是局部坐标，相对 collider）
    let transform = |p: crate::Vec3| -> [f64; 3] {
        let wp = rot * (cpos_v + p) + t;
        [wp.x as f64, wp.y as f64, wp.z as f64]
    };
    let mut add_quad = |vs: &mut Vec<[f64; 3]>, fs: &mut Vec<[u32; 4]>, pts: [[f64; 3]; 4]| {
        let base = vs.len() as u32;
        for p in pts {
            vs.push(p);
        }
        fs.push([base, base + 1, base + 2, base + 3]);
    };
    match shape.as_typed_shape() {
        TypedShape::Ball(b) => {
            let r = b.radius as f64;
            let pts = [
                transform(crate::Vec3::new(-r, -r, -r)),
                transform(crate::Vec3::new(r, -r, -r)),
                transform(crate::Vec3::new(r, r, -r)),
                transform(crate::Vec3::new(-r, r, -r)),
                transform(crate::Vec3::new(-r, -r, r)),
                transform(crate::Vec3::new(r, -r, r)),
                transform(crate::Vec3::new(r, r, r)),
                transform(crate::Vec3::new(-r, r, r)),
            ];
            add_quad(vertices, faces, [pts[0], pts[1], pts[2], pts[3]]);
            add_quad(vertices, faces, [pts[4], pts[5], pts[6], pts[7]]);
            add_quad(vertices, faces, [pts[0], pts[1], pts[5], pts[4]]);
            add_quad(vertices, faces, [pts[2], pts[3], pts[7], pts[6]]);
            add_quad(vertices, faces, [pts[0], pts[3], pts[7], pts[4]]);
            add_quad(vertices, faces, [pts[1], pts[2], pts[6], pts[5]]);
        }
        TypedShape::Capsule(c) => {
            let a = transform(crate::Vec3::new(
                c.segment.a.x as f64,
                c.segment.a.y as f64,
                c.segment.a.z as f64,
            ));
            let b = transform(crate::Vec3::new(
                c.segment.b.x as f64,
                c.segment.b.y as f64,
                c.segment.b.z as f64,
            ));
            let r = c.radius as f64;
            // 胶囊 → 两端点 ± r 近似为细长盒
            let p0 = [a[0], a[1], a[2]];
            let p1 = [b[0], b[1], b[2]];
            let pts = [
                [p0[0] - r, p0[1] - r, p0[2] - r],
                [p0[0] + r, p0[1] - r, p0[2] - r],
                [p0[0] + r, p0[1] + r, p0[2] - r],
                [p0[0] - r, p0[1] + r, p0[2] - r],
                [p1[0] - r, p1[1] - r, p1[2] - r],
                [p1[0] + r, p1[1] - r, p1[2] - r],
                [p1[0] + r, p1[1] + r, p1[2] - r],
                [p1[0] - r, p1[1] + r, p1[2] - r],
            ];
            add_quad(vertices, faces, [pts[0], pts[1], pts[2], pts[3]]);
            add_quad(vertices, faces, [pts[4], pts[5], pts[6], pts[7]]);
            add_quad(vertices, faces, [pts[0], pts[1], pts[5], pts[4]]);
            add_quad(vertices, faces, [pts[3], pts[2], pts[6], pts[7]]);
            add_quad(vertices, faces, [pts[0], pts[3], pts[7], pts[4]]);
            add_quad(vertices, faces, [pts[1], pts[2], pts[6], pts[5]]);
        }
        TypedShape::Cuboid(c) => {
            let he = c.half_extents;
            let pts = [
                transform(crate::Vec3::new(-he.x, -he.y, -he.z)),
                transform(crate::Vec3::new(he.x, -he.y, -he.z)),
                transform(crate::Vec3::new(he.x, he.y, -he.z)),
                transform(crate::Vec3::new(-he.x, he.y, -he.z)),
                transform(crate::Vec3::new(-he.x, -he.y, he.z)),
                transform(crate::Vec3::new(he.x, -he.y, he.z)),
                transform(crate::Vec3::new(he.x, he.y, he.z)),
                transform(crate::Vec3::new(-he.x, he.y, he.z)),
            ];
            add_quad(vertices, faces, [pts[0], pts[1], pts[2], pts[3]]);
            add_quad(vertices, faces, [pts[4], pts[5], pts[6], pts[7]]);
            add_quad(vertices, faces, [pts[0], pts[1], pts[5], pts[4]]);
            add_quad(vertices, faces, [pts[2], pts[3], pts[7], pts[6]]);
            add_quad(vertices, faces, [pts[0], pts[3], pts[7], pts[4]]);
            add_quad(vertices, faces, [pts[1], pts[2], pts[6], pts[5]]);
        }
        TypedShape::ConvexPolyhedron(cp) => {
            // 凸包：仅导出顶点（点云，按 body pose 变换）。Face 拓扑结构复杂，
            // 虚拟碰撞空间少用 convex；顶点集已足以显示形状位置与范围。
            for v in cp.points().iter() {
                let wp = transform(crate::Vec3::new(v.x, v.y, v.z));
                vertices.push(wp);
            }
        }
        TypedShape::TriMesh(tm) => {
            let base = vertices.len() as u32;
            for v in tm.vertices().iter() {
                vertices.push(transform(crate::Vec3::new(v.x, v.y, v.z)));
            }
            for tri in tm.indices().iter() {
                let a = base + tri[0] as u32;
                let b = base + tri[1] as u32;
                let c2 = base + tri[2] as u32;
                // 三角形：OBJ 用 f a b c（第 4 顶点 = 第 3）
                faces.push([a, b, c2, c2]);
            }
        }
        _ => {
            // 其他 shape（未知）：忽略
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_setCenterOfMass<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
    x: jdouble,
    y: jdouble,
    z: jdouble,
) {
    with_handle(handle, |scene| {
        let mut sable_data = scene.sable_data.write().unwrap();
        let info = sable_data
            .level_colliders
            .get_mut(&(id as LevelColliderID))
            .unwrap();
        info.center_of_mass = Some(DVec3::new(x as Real, y as Real, z as Real));
        let mut sim_data = scene.sim_data.write().unwrap();
        update_collider_aabb(&mut sim_data, info);
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_setLocalBounds<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
    min_x: jint,
    min_y: jint,
    min_z: jint,
    max_x: jint,
    max_y: jint,
    max_z: jint,
) {
    with_handle(handle, |scene| {
        let physics_state = get_physics_state();
        let collider_map = &physics_state.voxel_collider_map;
        let mut sable_data = scene.sable_data.write().unwrap();
        let SableSceneData {
            level_colliders,
            main_level_chunks,
            ..
        } = &mut *sable_data;

        let info = level_colliders.get_mut(&(id as LevelColliderID)).unwrap();
        info.set_local_bounds(
            crate::prec::ivec3(min_x as i64, min_y as i64, min_z as i64),
            crate::prec::ivec3(max_x as i64, max_y as i64, max_z as i64),
            main_level_chunks,
            collider_map,
        );
        let mut sim_data = scene.sim_data.write().unwrap();
        update_collider_aabb(&mut sim_data, info);
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_createSubLevel<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
    pose: JDoubleArray<'local>,
    is_static: jboolean,
) {
    let mut pose_arr: [jdouble; 7] = [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0];
    env.get_double_array_region(pose, 0, &mut pose_arr).unwrap();

    let quat = Quat::from_xyzw(
        pose_arr[3] as Real,
        pose_arr[4] as Real,
        pose_arr[5] as Real,
        pose_arr[6] as Real,
    );

    // is_static=true -> Fixed rigid body (true static: never integrated, holds others).
    // is_static=false -> Dynamic (original behaviour).
    let mut rigid_body = if is_static != 0 {
        RigidBodyBuilder::fixed()
    } else {
        RigidBodyBuilder::dynamic().ccd_enabled(true)
    }
    .translation(Vec3::new(
        pose_arr[0] as Real,
        pose_arr[1] as Real,
        pose_arr[2] as Real,
    ))
    .build();
    rigid_body.set_rotation(quat, false);
    // NOTE: keep LevelCollider::is_static=false even for fixed bodies so the precise
    // cached AABB (setLocalBounds) is used for broad-phase, not the giant static world box.
    let activation_params = rigid_body.activation_mut();
    activation_params.angular_threshold = 0.15;
    activation_params.normalized_linear_threshold = 0.15;

    with_handle(handle, |scene| {
        rigid_body.set_linear_damping(scene.universal_drag);
        rigid_body.set_angular_damping(scene.universal_drag);
        rigid_body.enable_gyroscopic_forces(true);

        let mut sim_data = scene.sim_data.write().unwrap();
        let sim_data = &mut *sim_data;
        let mut sable_data = scene.sable_data.write().unwrap();

        let handle = sim_data.rigid_body_set.insert(rigid_body);

        // make a level collider
        let collider = ColliderBuilder::new(SharedShape::new(LevelCollider::new(
            Some(id as LevelColliderID),
            false,
        )))
        .friction(0.525)
        .active_events(ActiveEvents::CONTACT_FORCE_EVENTS)
        .active_hooks(ActiveHooks::MODIFY_SOLVER_CONTACTS)
        .density(0.0)
        .collision_groups(LEVEL_GROUP)
        .build();

        let collider_handle = sim_data.collider_set.insert_with_parent(
            collider,
            handle,
            &mut sim_data.rigid_body_set,
        );

        sable_data.level_colliders.insert(
            id as LevelColliderID,
            ActiveLevelColliderInfo::new(collider_handle),
        );

        sable_data
            .rigid_bodies
            .insert(id as LevelColliderID, handle);
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_removeSubLevel<
    'local,
>(
    mut _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
) {
    with_handle(handle, |scene| {
        let mut sable_data = scene.sable_data.write().unwrap();

        sable_data.level_colliders.remove(&(id as LevelColliderID));
        // ★ 2026-09-06 崩溃兜底：并发/迁移后可能已删除/从未创建 → 找不到 body
        //   时 warn+skip（原 .expect → non-unwinding panic → JVM abort）。
        let Some(handle) = sable_data.rigid_bodies.remove(&(id as LevelColliderID)) else {
            log::warn!("[sabledbg] removeSubLevel skip id={} not registered", id);
            return;
        };

        let mut sim_data = scene.sim_data.write().unwrap();

        let sim_data = &mut *sim_data;
        let rigid_body_set = &mut sim_data.rigid_body_set;
        let island_manager = &mut sim_data.island_manager;
        let collider_set = &mut sim_data.collider_set;
        let impulse_joint_set = &mut sim_data.impulse_joint_set;
        let multibody_joint_set = &mut sim_data.multibody_joint_set;

        rigid_body_set.remove(
            handle,
            island_manager,
            collider_set,
            impulse_joint_set,
            multibody_joint_set,
            true,
        );
    })
}

pub fn insert_block_octree(
    collider_map: &VoxelColliderMap,
    octree: &mut SubLevelOctree,
    state: &BlockState,
    remove: bool,
    x: i32,
    y: i32,
    z: i32,
) {
    let block_collider_id = state.0;
    let block_collider = if block_collider_id > 0 {
        Some(
            collider_map
                .voxel_colliders
                .get(block_collider_id as usize - 1)
                .unwrap(),
        )
    } else {
        None
    };
    let voxel_state = state.1;

    let solid = voxel_state != Interior
        && voxel_state != VoxelPhysicsState::Empty
        && (block_collider_id > 0
            && !block_collider
                .unwrap()
                .as_ref()
                .unwrap()
                .collision_boxes
                .is_empty());

    if remove && !solid {
        octree.insert(x, y, z, -1);
    }

    if solid {
        octree.insert(x, y, z, block_collider_id as i32);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_addChunk<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    x: jint,
    y: jint,
    z: jint,
    data: JIntArray<'local>,
    global: jboolean,
    object_id: jint,
) {
    let mut ints: [jint; 4096] = [0; 4096];
    env.get_int_array_region(data, 0, &mut ints).unwrap();

    let mut blocks = Vec::with_capacity(ints.len());

    for block in ints {
        // split it in half
        let block_collider_id = (block >> 16) as u16;
        let voxel_state_id = (block & 0xFFFF) as u16;

        blocks.push((
            block_collider_id as u32,
            ALL_VOXEL_PHYSICS_STATES[voxel_state_id as usize],
        ));
    }

    let chunk = ChunkSection::new(blocks);

    with_handle(handle, |scene| {
        let physics_state = get_physics_state();
        let collider_map = &physics_state.voxel_collider_map;
        let mut sable_data = scene.sable_data.write().unwrap();
        let SableSceneData {
            main_level_chunks,
            level_colliders,
            octree_chunks,
            ..
        } = &mut *sable_data;

        main_level_chunks.insert(pack_section_pos(x, y, z), chunk);

        let chunk = main_level_chunks.get(&pack_section_pos(x, y, z)).unwrap();
        if global == 0 {
            if object_id != -1 {
                // ★ 2026-09-06 防御：陪体 id 未注册（createSubLevel 未建/已删）→ 安全跳过
                //   （原 .unwrap() panic → 中止进程）。陪体由 Java 创建链保证顺序，缺建时
                //   由 next materialize 补齐；此处仅跳过且记录日志。
                let Some(body) = level_colliders.get_mut(&(object_id as LevelColliderID)) else {
                    log::warn!("[sabledbg] addChunk-own skip id={} not registered", object_id);
                    return;
                };

                // ★ 2026-09-03 掉穿根因修复：world_vs_world 的 get_chunk 只认
                //   collider 自己的 chunk_map（own）或回退 main_level_chunks。
                //   只写 octree 不够：octree 仅用于 find_collision_pairs 配对，
                //   get_chunk 用 chunk_map。此前 chunk_map 恒为 None → 结构/陪体
                //   全部回退 main → 块互相覆盖 + far section key 与查询 st>>4 错位
                //   → 全 c1miss → 0 manifold → 结构穿落。
                //   现在给对应 body 建 own chunk_map（far section key），
                //   与 world_vs_world 的 st>>4 查询对齐，结构/陪体各自独立命中。
                if body.chunk_map.is_none() {
                    body.chunk_map = Some(ChunkMap::new());
                }
                if let Some(own) = body.chunk_map.as_mut() {
                    own.insert(pack_section_pos(x, y, z), chunk.clone());
                    // ★ 2026-09-03 定结构/陪体 own map key 与查询错位：打印入参&pack key
                    crate::sabledbg!("addChunk-own", 1000,
                        "[sabledbg] addChunk-own id={} x={} y={} z={} key={} len={}",
                        object_id, x, y, z, pack_section_pos(x, y, z), own.len()
                    );
                }

                // ★ 2026-09-03 穿透根因修复：更新 own chunk_map 后【强制从 own chunk_map
                //   重建 octree】（不再走 insert_chunk 增量——其 range guard 可能因 bounds
                //   顺序/越界跳过，导致【结构自身 octree 空】→ find_collision_pairs 遍历不到
                //   → 0 manifold → 结构穿透）。重建保证 octree 恒与 chunk_map 一致。
                body.rebuild_octree_from_own_chunks(collider_map);
            }
        } else {
            for bx in 0..16 {
                for by in 0..16 {
                    for bz in 0..16 {
                        let block = chunk.get_block(bx, by, bz);
                        let x = bx + (x << CHUNK_SHIFT);
                        let y = by + (y << CHUNK_SHIFT);
                        let z = bz + (z << CHUNK_SHIFT);

                        // insert into level octree
                        let ox = x >> OCTREE_CHUNK_SHIFT;
                        let oy = y >> OCTREE_CHUNK_SHIFT;
                        let oz = z >> OCTREE_CHUNK_SHIFT;

                        let mut octree_chunk = octree_chunks.get_mut(&pack_section_pos(ox, oy, oz));

                        if octree_chunk.is_none() {
                            octree_chunks
                                .insert(pack_section_pos(ox, oy, oz), OctreeChunkSection::new());
                            octree_chunk = octree_chunks.get_mut(&pack_section_pos(ox, oy, oz));
                        }

                        let Some(octree_chunk) = octree_chunk else {
                            panic!("No octree chunk!")
                        };

                        if block.0 == 0 {
                            insert_block_octree(
                                collider_map,
                                &mut octree_chunk.liquid_octree,
                                &block,
                                false,
                                x & (OCTREE_CHUNK_SIZE - 1),
                                y & (OCTREE_CHUNK_SIZE - 1),
                                z & (OCTREE_CHUNK_SIZE - 1),
                            );
                            insert_block_octree(
                                collider_map,
                                &mut octree_chunk.octree,
                                &block,
                                false,
                                x & (OCTREE_CHUNK_SIZE - 1),
                                y & (OCTREE_CHUNK_SIZE - 1),
                                z & (OCTREE_CHUNK_SIZE - 1),
                            );
                        } else {
                            if collider_map.voxel_colliders[(block.0 - 1) as usize]
                                .as_ref()
                                .unwrap()
                                .is_fluid
                            {
                                insert_block_octree(
                                    collider_map,
                                    &mut octree_chunk.liquid_octree,
                                    &block,
                                    false,
                                    x & (OCTREE_CHUNK_SIZE - 1),
                                    y & (OCTREE_CHUNK_SIZE - 1),
                                    z & (OCTREE_CHUNK_SIZE - 1),
                                );
                            } else {
                                insert_block_octree(
                                    collider_map,
                                    &mut octree_chunk.octree,
                                    &block,
                                    false,
                                    x & (OCTREE_CHUNK_SIZE - 1),
                                    y & (OCTREE_CHUNK_SIZE - 1),
                                    z & (OCTREE_CHUNK_SIZE - 1),
                                );
                            }
                        }
                    }
                }
            }
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_removeChunk<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    x: jint,
    y: jint,
    z: jint,
    global: jboolean,
) {
    with_handle(handle, |scene| {
        let physics_state = get_physics_state();
        let collider_map = &physics_state.voxel_collider_map;
        let mut sable_data = scene.sable_data.write().unwrap();

        sable_data
            .main_level_chunks
            .remove(&pack_section_pos(x, y, z));

        if global > 0 {
            let octree_chunk = sable_data.octree_chunks.get_mut(&pack_section_pos(
                (x << CHUNK_SHIFT) >> OCTREE_CHUNK_SHIFT,
                (y << CHUNK_SHIFT) >> OCTREE_CHUNK_SHIFT,
                (z << CHUNK_SHIFT) >> OCTREE_CHUNK_SHIFT,
            ));

            if let Some(octree_chunk) = octree_chunk {
                for bx in 0..16 {
                    for by in 0..16 {
                        for bz in 0..16 {
                            let x = bx + (x << CHUNK_SHIFT);
                            let y = by + (y << CHUNK_SHIFT);
                            let z = bz + (z << CHUNK_SHIFT);

                            insert_block_octree(
                                collider_map,
                                &mut octree_chunk.octree,
                                &(0, VoxelPhysicsState::Empty),
                                true,
                                x & (OCTREE_CHUNK_SIZE - 1),
                                y & (OCTREE_CHUNK_SIZE - 1),
                                z & (OCTREE_CHUNK_SIZE - 1),
                            );
                            insert_block_octree(
                                collider_map,
                                &mut octree_chunk.liquid_octree,
                                &(0, VoxelPhysicsState::Empty),
                                true,
                                x & (OCTREE_CHUNK_SIZE - 1),
                                y & (OCTREE_CHUNK_SIZE - 1),
                                z & (OCTREE_CHUNK_SIZE - 1),
                            );
                        }
                    }
                }

                if octree_chunk.octree.buffer[0] == 0 && octree_chunk.liquid_octree.buffer[0] == 0 {
                    sable_data.octree_chunks.remove(&pack_section_pos(
                        (x << CHUNK_SHIFT) >> OCTREE_CHUNK_SHIFT,
                        (y << CHUNK_SHIFT) >> OCTREE_CHUNK_SHIFT,
                        (z << CHUNK_SHIFT) >> OCTREE_CHUNK_SHIFT,
                    ));
                }
            }
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_changeBlock<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    x: jint,
    y: jint,
    z: jint,
    block: jint,
) {
    let block_collider_id = (block >> 16) as u16;
    let voxel_state_id = (block & 0xFFFF) as u16;

    with_handle(handle, |scene| {
        let physics_state = get_physics_state();
        let collider_map = &physics_state.voxel_collider_map;
        let mut sable_data = scene.sable_data.write().unwrap();
        let SableSceneData {
            main_level_chunks,
            level_colliders,
            octree_chunks,
            ..
        } = &mut *sable_data;

        let chunk = main_level_chunks.get_mut(&pack_section_pos(x >> 4, y >> 4, z >> 4));

        if let Some(chunk) = chunk {
            let block_state = (
                block_collider_id as u32,
                ALL_VOXEL_PHYSICS_STATES[voxel_state_id as usize],
            );

            chunk.set_block(x & 15, y & 15, z & 15, block_state);

            let mut any = false;
            for (_, sable_body) in level_colliders.iter_mut() {
                if sable_body.contains(x, y, z) {
                    sable_body.insert_block(x, y, z, &block_state, true, collider_map);
                    any = true;
                    break;
                }
            }

            if !any {
                // insert into level octree
                let ox = x >> OCTREE_CHUNK_SHIFT;
                let oy = y >> OCTREE_CHUNK_SHIFT;
                let oz = z >> OCTREE_CHUNK_SHIFT;

                let mut octree_chunk = octree_chunks.get_mut(&pack_section_pos(ox, oy, oz));

                if octree_chunk.is_none() {
                    octree_chunks.insert(pack_section_pos(ox, oy, oz), OctreeChunkSection::new());
                    octree_chunk = octree_chunks.get_mut(&pack_section_pos(ox, oy, oz));
                }

                let Some(octree_chunk) = octree_chunk else {
                    panic!("No octree chunk!")
                };

                if block_collider_id == 0 {
                    insert_block_octree(
                        collider_map,
                        &mut octree_chunk.octree,
                        &block_state,
                        true,
                        x & (OCTREE_CHUNK_SIZE - 1),
                        y & (OCTREE_CHUNK_SIZE - 1),
                        z & (OCTREE_CHUNK_SIZE - 1),
                    );
                    insert_block_octree(
                        collider_map,
                        &mut octree_chunk.liquid_octree,
                        &block_state,
                        true,
                        x & (OCTREE_CHUNK_SIZE - 1),
                        y & (OCTREE_CHUNK_SIZE - 1),
                        z & (OCTREE_CHUNK_SIZE - 1),
                    );
                } else {
                    if collider_map
                        .voxel_colliders
                        .get(block_collider_id as usize - 1)
                        .unwrap()
                        .as_ref()
                        .unwrap()
                        .is_fluid
                    {
                        insert_block_octree(
                            collider_map,
                            &mut octree_chunk.liquid_octree,
                            &block_state,
                            false,
                            x & (OCTREE_CHUNK_SIZE - 1),
                            y & (OCTREE_CHUNK_SIZE - 1),
                            z & (OCTREE_CHUNK_SIZE - 1),
                        );
                    } else {
                        insert_block_octree(
                            collider_map,
                            &mut octree_chunk.octree,
                            &block_state,
                            false,
                            x & (OCTREE_CHUNK_SIZE - 1),
                            y & (OCTREE_CHUNK_SIZE - 1),
                            z & (OCTREE_CHUNK_SIZE - 1),
                        );
                    }
                }
            }
        }
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_setMassProperties<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
    mass: jdouble,
    center_of_mass: JDoubleArray<'local>,
    inertia: JDoubleArray<'local>,
) {
    let mut com: [jdouble; 3] = [0.0, 0.0, 0.0];
    env.get_double_array_region(center_of_mass, 0, &mut com)
        .unwrap();

    let mut inertia_arr: [jdouble; 9] = [0.0; 9];
    env.get_double_array_region(inertia, 0, &mut inertia_arr)
        .unwrap();

    let inertia_tensor = Mat3::from_cols(
        Vec3::new(
            inertia_arr[0] as Real,
            inertia_arr[1] as Real,
            inertia_arr[2] as Real,
        ),
        Vec3::new(
            inertia_arr[3] as Real,
            inertia_arr[4] as Real,
            inertia_arr[5] as Real,
        ),
        Vec3::new(
            inertia_arr[6] as Real,
            inertia_arr[7] as Real,
            inertia_arr[8] as Real,
        ),
    );

    with_handle(handle, |scene| {
        let sable_data = scene.sable_data.read().unwrap();
        let mut sim_data = scene.sim_data.write().unwrap();

        let rb = &mut sim_data.rigid_body_set[sable_data.rigid_bodies[&(id as LevelColliderID)]];

        rb.set_additional_mass_properties(
            MassProperties::with_inertia_matrix(Vec3::ZERO, mass as Real, inertia_tensor.into()),
            true,
        );
    })
}

/// Teleports the object to the given position.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_teleportObject<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
    x: jdouble,
    y: jdouble,
    z: jdouble,
    i: jdouble,
    j: jdouble,
    k: jdouble,
    r: jdouble,
) {
    with_handle(handle, |scene| {
        let sable_data = scene.sable_data.read().unwrap();
        let mut sim_data = scene.sim_data.write().unwrap();

        let rb = &mut sim_data.rigid_body_set[sable_data.rigid_bodies[&(id as LevelColliderID)]];

        let mut pose = *rb.position();
        pose.translation = Vec3::new(x as Real, y as Real, z as Real);
        pose.rotation = Quat::from_xyzw(i as Real, j as Real, k as Real, r as Real);
        rb.set_position(pose, true);
    })
}

/// Wakes up an object.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_wakeUpObject<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
) {
    with_handle(handle, |scene| {
        let sable_data = scene.sable_data.read().unwrap();
        let mut sim_data = scene.sim_data.write().unwrap();
        let rb = &mut sim_data.rigid_body_set[sable_data.rigid_bodies[&(id as LevelColliderID)]];
        rb.wake_up(true);
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_addLinearAngularVelocities<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
    linear_x: jdouble,
    linear_y: jdouble,
    linear_z: jdouble,
    angular_x: jdouble,
    angular_y: jdouble,
    angular_z: jdouble,
    wake_up: jboolean,
) {
    with_handle(handle, |scene| {
        let sable_data = scene.sable_data.read().unwrap();
        let mut sim_data = scene.sim_data.write().unwrap();
        let rb = get_rigid_body_mut(&mut sim_data, &sable_data, id as LevelColliderID);

        if wake_up == 0 && rb.is_sleeping() {
            return;
        }

        rb.set_linvel(
            rb.linvel() + Vec3::new(linear_x as Real, linear_y as Real, linear_z as Real),
            wake_up > 0,
        );
        rb.set_angvel(
            rb.angvel() + Vec3::new(angular_x as Real, angular_y as Real, angular_z as Real),
            wake_up > 0,
        );
    })
}

/// Clears & queries all collisions
///
/// TODO: Do not pass body IDs as doubles, stupid as hell lmao
///
/// A collision is formatted as follows:
/// [body_a, body_b, force_amount, local_normal_a, local_normal_b, local_point_a, local_point_b]
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_clearCollisions<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> JDoubleArray<'local> {
    let arr: Vec<jdouble> = with_handle(handle, |scene| {
        let mut reported = scene.reported_collisions.borrow_mut();

        let max_collisions = 100;

        reported.truncate(max_collisions);
        let mut arr: Vec<jdouble> = Vec::with_capacity(reported.len() * 15);

        for collision in reported.iter() {
            let body_a = if let Some(id) = collision.body_a {
                id as jdouble
            } else {
                -1.0
            };

            let body_b = if let Some(id) = collision.body_b {
                id as jdouble
            } else {
                -1.0
            };

            arr.push(body_a);
            arr.push(body_b);
            arr.push(collision.force_amount as jdouble);
            arr.push(collision.local_normal_a.x as jdouble);
            arr.push(collision.local_normal_a.y as jdouble);
            arr.push(collision.local_normal_a.z as jdouble);
            arr.push(collision.local_normal_b.x as jdouble);
            arr.push(collision.local_normal_b.y as jdouble);
            arr.push(collision.local_normal_b.z as jdouble);
            arr.push(collision.local_point_a.x as jdouble);
            arr.push(collision.local_point_a.y as jdouble);
            arr.push(collision.local_point_a.z as jdouble);
            arr.push(collision.local_point_b.x as jdouble);
            arr.push(collision.local_point_b.y as jdouble);
            arr.push(collision.local_point_b.z as jdouble);
        }

        reported.clear();

        arr
    });

    let double_array = _env.new_double_array(arr.len() as jint).unwrap();
    _env.set_double_array_region(&double_array, 0, &arr)
        .unwrap();

    double_array
}

/// Applies a force to a body
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_applyForce<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
    x: jdouble,
    y: jdouble,
    z: jdouble,
    fx: jdouble,
    fy: jdouble,
    fz: jdouble,
    wake_up: jboolean,
) {
    with_handle(handle, |scene| {
        let sable_data = scene.sable_data.read().unwrap();
        let mut sim_data = scene.sim_data.write().unwrap();

        let body = sable_data
            .rigid_bodies
            .get(&(id as LevelColliderID))
            .unwrap();
        let rb = &mut sim_data.rigid_body_set[*body];

        if wake_up == 0 && rb.is_sleeping() {
            return;
        }

        let force: Vec3 = rb
            .rotation()
            .mul_vec3(Vec3::new(fx as Real, fy as Real, fz as Real));
        let force_pos = rb
            .position()
            .transform_point(Vec3::new(x as Real, y as Real, z as Real));

        rb.apply_impulse(force, wake_up > 0);

        let torque_impulse = (force_pos - rb.position().translation).cross(force);
        rb.apply_torque_impulse(torque_impulse, wake_up > 0);
    })
}

/// Applies a force and torque
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_applyForceAndTorque<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
    fx: jdouble,
    fy: jdouble,
    fz: jdouble,
    tx: jdouble,
    ty: jdouble,
    tz: jdouble,
    wake_up: jboolean,
) {
    with_handle(handle, |scene| {
        let sable_data = scene.sable_data.read().unwrap();
        let mut sim_data = scene.sim_data.write().unwrap();

        let body = sable_data
            .rigid_bodies
            .get(&(id as LevelColliderID))
            .unwrap();
        let rb = &mut sim_data.rigid_body_set[*body];

        if wake_up == 0 && rb.is_sleeping() {
            return;
        }

        let force: Vec3 = rb
            .rotation()
            .mul_vec3(Vec3::new(fx as Real, fy as Real, fz as Real));
        rb.apply_impulse(force, wake_up > 0);

        let torque: Vec3 = rb
            .rotation()
            .mul_vec3(Vec3::new(tx as Real, ty as Real, tz as Real));
        rb.apply_torque_impulse(torque, wake_up > 0);
    })
}

/// Gets the linear velocity of a body
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_getLinearVelocity<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
    store: JDoubleArray<'local>,
) {
    with_handle(handle, |scene| {
        let sable_data = scene.sable_data.read().unwrap();
        let sim_data = scene.sim_data.read().unwrap();

        let body = sable_data
            .rigid_bodies
            .get(&(id as LevelColliderID))
            .unwrap();
        let rb = &sim_data.rigid_body_set[*body];

        let vel = rb.linvel();

        _env.set_double_array_region(
            &store,
            0,
            &[vel.x as jdouble, vel.y as jdouble, vel.z as jdouble],
        )
        .unwrap();
    })
}

/// Gets the angular velocity of a body
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_getAngularVelocity<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
    store: JDoubleArray<'local>,
) {
    with_handle(handle, |scene| {
        let sable_data = scene.sable_data.read().unwrap();
        let sim_data = scene.sim_data.read().unwrap();

        let body = sable_data
            .rigid_bodies
            .get(&(id as LevelColliderID))
            .unwrap();
        let rb = &sim_data.rigid_body_set[*body];

        let vel = rb.angvel();

        _env.set_double_array_region(
            &store,
            0,
            &[vel.x as jdouble, vel.y as jdouble, vel.z as jdouble],
        )
        .unwrap();
    })
}
