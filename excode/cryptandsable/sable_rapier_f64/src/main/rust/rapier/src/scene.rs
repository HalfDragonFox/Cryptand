use crate::event_handler::SableEventHandler;
use crate::hooks::SablePhysicsHooks;
use crate::joints::SableJointSet;
use crate::rope::RopeMap;
use crate::{ActiveLevelColliderInfo, ReportedCollision};
use dashmap::DashMap;
use jni::JavaVM;
use crate::prec::Real;
use marten::level::{ChunkSection, OctreeChunkSection};
use rapier3d::dynamics::{
    CCDSolver, ImpulseJointSet, IntegrationParameters, IslandManager, MultibodyJointSet,
    RigidBodyHandle, RigidBodySet,
};
use rapier3d::geometry::{ColliderSet, DefaultBroadPhase, NarrowPhase};
use crate::IVec3;
use rapier3d::math::Vec3;
use rapier3d::pipeline::PhysicsPipeline;
use std::collections::HashMap;
use std::sync::Mutex;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, RwLock};

pub type LevelColliderID = usize;

pub trait ChunkAccess {
    #[allow(unused)]
    fn get_chunk_mut(&mut self, x: i32, y: i32, z: i32) -> Option<&mut ChunkSection>;
    fn get_chunk(&self, x: i32, y: i32, z: i32) -> Option<&ChunkSection>;
}

#[inline(always)]
pub fn pack_section_pos(i: i32, j: i32, k: i32) -> i64 {
    let mut l: i64 = 0;
    l |= (i as i64 & 4194303i64) << 42;
    l |= j as i64 & 1048575i64;
    l | (k as i64 & 4194303i64) << 20
}

pub type ChunkMap = HashMap<i64, ChunkSection>;

pub struct ReportedCollisionBuffer(Mutex<Vec<ReportedCollision>>);

impl ReportedCollisionBuffer {
    pub fn new() -> Self {
        Self(Mutex::new(Vec::with_capacity(16)))
    }

    pub fn borrow_mut(&self) -> std::sync::MutexGuard<'_, Vec<ReportedCollision>> {
        self.0.lock().unwrap()
    }
}

impl Default for ReportedCollisionBuffer {
    fn default() -> Self {
        Self::new()
    }
}

pub struct SimulationSceneData {
    pub pipeline: PhysicsPipeline,
    pub rigid_body_set: RigidBodySet,
    pub collider_set: ColliderSet,
    pub island_manager: IslandManager,
    pub broad_phase: DefaultBroadPhase,
    pub narrow_phase: NarrowPhase,
    pub impulse_joint_set: ImpulseJointSet,
    pub multibody_joint_set: MultibodyJointSet,
    pub ccd_solver: CCDSolver,
    pub physics_hooks: SablePhysicsHooks,
    pub event_handler: SableEventHandler,
}

pub struct SableSceneData {
    /// A 3-dimensional map of chunk sections for collision.
    /// chunk coordinates -> chunk section
    pub main_level_chunks: ChunkMap,
    pub octree_chunks: HashMap<i64, OctreeChunkSection>,

    /// The companion joint set
    pub joint_set: SableJointSet,

    /// Rope map
    pub rope_map: RopeMap,

    pub level_colliders: HashMap<LevelColliderID, ActiveLevelColliderInfo>,
    pub rigid_bodies: HashMap<LevelColliderID, RigidBodyHandle>,

    // ===== 2026-09-04 shape cache (fixed companion bodies, evictable) =====
    /// Shape cache entries: fixed shape bodies (companion/ground) registered on create.
    /// NOT dynamic structure bodies (those are never evicted).
    pub shape_cache: HashMap<LevelColliderID, ShapeCacheEntry>,
    /// Max cached shape bodies (count). Negative = unlimited. 0 = cache disabled.
    pub shape_cache_limit: std::sync::atomic::AtomicI64,
    /// Minimum number of entries removed per eviction pass (batch; e.g. 3).
    pub shape_cache_min_evict: std::sync::atomic::AtomicI64,
    /// Set true when setShapeCacheLimit shrinks the limit; next step evicts ONCE
    /// down to the new limit. Expanding or equal never triggers eviction.
    pub shape_cache_shrink_pending: std::sync::atomic::AtomicBool,
}

/// A cached shape body entry (fixed companion / ground).
/// Weight = usage frequency (u8, saturates at 255 and stops increasing).
/// Footprint = number of colliders (small structures evict first on tie).
pub struct ShapeCacheEntry {
    pub id: LevelColliderID,
    /// Usage weight: 0..=255 (saturating_add, never exceeds 255).
    pub weight: u8,
    /// Number of colliders attached (structure size proxy).
    pub footprint: u32,
    /// Collider handles (raw usize) for touch-tracking in narrow phase.
    pub colliders: Vec<usize>,
    /// Estimated resident bytes (for logging).
    pub bytes: usize,
}

impl ShapeCacheEntry {
    /// Bump usage weight; saturates at 255 (no further increase).
    #[inline]
    pub fn touch(&mut self) {
        self.weight = self.weight.saturating_add(1);
    }
}

impl SableSceneData {
    /// Estimated total cached footprint (count).
    pub fn shape_cache_len(&self) -> usize {
        self.shape_cache.len()
    }

    /// True if the current cache count exceeds a valid (>=0) limit.
    pub fn shape_cache_over_limit(&self) -> bool {
        let limit = self.shape_cache_limit.load(std::sync::atomic::Ordering::Relaxed);
        if limit < 0 {
            return false;
        }
        (self.shape_cache.len() as i64) > limit
    }
}

/// A physics scene
pub struct PhysicsScene {
    pub sim_data: RwLock<SimulationSceneData>,
    pub sable_data: Arc<RwLock<SableSceneData>>,

    /// All collisions substantial enough to be considered for collision events.
    pub reported_collisions: Arc<ReportedCollisionBuffer>,

    pub manifold_info_map: Arc<SableManifoldInfoMap>,

    pub current_step_vm: Option<Arc<JavaVM>>,

    /// The handle to a static rigidbody
    pub ground_handle: Option<RigidBodyHandle>,

    /// The current gravity vector for all bodies. [m/s^2]
    pub gravity: Vec3,

    /// Universal linear drag applied to all bodies
    pub universal_drag: Real,

    /// Per-scene integration parameters (dt / contact softness / solver iterations).
    /// ★ 2026-09-02 多 scene 并行：原为全局 PHYSICS_STATE 单例，step 全程持全局读锁 →
    ///   多线程 step 不同 scene 被 RwLock 语义串行化（读锁存在时写者等待、后续读者阻塞）。
    ///   迁到 per-scene 后 step 不再触碰全局锁 → 多个 scene 可同时计算（RwLock 读读并行）。
    pub integration_parameters: RwLock<IntegrationParameters>,
}

#[derive(Default)]
pub struct SableManifoldInfoMap {
    pub list: DashMap<usize, SableManifoldInfo>,
    pub counter: AtomicUsize,
}

impl SableManifoldInfoMap {
    pub fn clear(&self) {
        self.list.clear();
        self.counter.store(0, Ordering::Relaxed);
    }
}

pub struct SableManifoldInfo {
    pub pos_a: IVec3,
    pub pos_b: IVec3,
    pub col_a: usize,
    pub col_b: usize,
}

impl ChunkAccess for SableSceneData {
    fn get_chunk_mut(&mut self, x: i32, y: i32, z: i32) -> Option<&mut ChunkSection> {
        self.main_level_chunks.get_mut(&pack_section_pos(x, y, z))
    }

    fn get_chunk(&self, x: i32, y: i32, z: i32) -> Option<&ChunkSection> {
        self.main_level_chunks.get(&pack_section_pos(x, y, z))
    }
}

impl SableSceneData {
    pub fn get_octree_chunk(&self, x: i32, y: i32, z: i32) -> Option<&OctreeChunkSection> {
        self.octree_chunks.get(&pack_section_pos(x, y, z))
    }

    pub fn get_octree_chunk_mut(
        &mut self,
        x: i32,
        y: i32,
        z: i32,
    ) -> Option<&mut OctreeChunkSection> {
        self.octree_chunks.get_mut(&pack_section_pos(x, y, z))
    }
}
