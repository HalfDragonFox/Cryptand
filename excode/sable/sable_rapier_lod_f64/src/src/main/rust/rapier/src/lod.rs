//! 世界碰撞空间过滤缓存（2026-09-08 Cryptand 碰撞 LOD 第一层：无损 4³ 子块粗筛）
//!
//! 语义：静态世界碰撞查询（dispatcher::SableDispatcher::static_world_vs_collider）
//! 原为“刚体 AABB 覆盖域内逐方块 × 逐盒 convex_convex”。本模块把每 16³ chunk 的
//! 非空方块碰撞盒按 4³ 子块预合并为子块 AABB（[Option<SubBox>; 64]），查询时先以
//! 刚体 AABB 与子块 AABB 粗筛 —— 与子块不相交（或子块为空）的区域整块跳过，
//! 命中的子块仍按原逻辑逐方块精查 → 语义与原来完全一致（不漏、不误、无重复），
//! 仅剔除空/远区域 → 纯加速（行为零变化）。
//!
//! 缓存维护：SableSceneData.lod_chunk_cache，在 addChunk / removeChunk / changeBlock
//! （唯一改变 chunk 内容/存在的入口）处整 chunk 重建/移除（每重建 4096 方块遍历，
//! 廉价；方块变化频率低）。
//!
//! 距离 LOD（后续层）：将来可在此子块级再加“按刚体-世界距离选择更粗合并级/接触
//! 上限”等降细节策略；本层不改变任何碰撞结果。

use rapier3d_f64::glamx::IVec3;

use crate::scene::pack_section_pos;
use crate::voxel_collider::VoxelColliderMap;
use marten::level::ChunkSection;

/// 子块粒度：4³ 方块（每 chunk 16³ = 64 子块）
pub const SUB_SHIFT: i32 = 2;
pub const SUB_SIZE: i32 = 1 << SUB_SHIFT; // 4
pub const SUBS_PER_CHUNK: usize = (16 / SUB_SIZE).pow(3) as usize; // 64

/// 单个 4³ 子块内全部非空非流体方块碰撞盒的合并包围盒（方块局部坐标已加方块原点）。
#[derive(Clone, Copy, Debug)]
pub struct SubBox {
    pub min_x: f64,
    pub min_y: f64,
    pub min_z: f64,
    pub max_x: f64,
    pub max_y: f64,
    pub max_z: f64,
}

impl SubBox {
    /// 与该盒子相交？（用于与刚体 AABB 粗筛）
    #[inline]
    pub fn intersects_aabb(
        &self,
        a_min_x: f64,
        a_min_y: f64,
        a_min_z: f64,
        a_max_x: f64,
        a_max_y: f64,
        a_max_z: f64,
    ) -> bool {
        self.min_x <= a_max_x
            && self.max_x >= a_min_x
            && self.min_y <= a_max_y
            && self.max_y >= a_min_y
            && self.min_z <= a_max_z
            && self.max_z >= a_min_z
    }

    #[inline]
    fn merge_into(&mut self, min_x: f64, min_y: f64, min_z: f64, max_x: f64, max_y: f64, max_z: f64) {
        if min_x < self.min_x {
            self.min_x = min_x;
        }
        if min_y < self.min_y {
            self.min_y = min_y;
        }
        if min_z < self.min_z {
            self.min_z = min_z;
        }
        if max_x > self.max_x {
            self.max_x = max_x;
        }
        if max_y > self.max_y {
            self.max_y = max_y;
        }
        if max_z > self.max_z {
            self.max_z = max_z;
        }
    }
}

/// 每 chunk 的 4³ 子块合并盒表（None = 该子块无碰撞盒）。
pub type ChunkLod = [Option<SubBox>; SUBS_PER_CHUNK];

/// 方块在 chunk 内的 4³ 子块索引（x 主序：(x*4+y)*4+z —— 与 dispatcher 查询一致）。
#[inline]
pub fn sub_index(bx: i32, by: i32, bz: i32) -> usize {
    ((((bx >> SUB_SHIFT) << 2) | (by >> SUB_SHIFT)) << 2 | (bz >> SUB_SHIFT)) as usize
}

/// 重建一个 chunk 的子块合并盒表（chunk_x/y/z = chunk 坐标）。
/// 与 dispatcher 判定语义一致：block_id==0 跳过；流体 collider 跳过；dynamic 方块
/// 经 VoxelColliderMap::get 的 per-position 覆盖规则（构建时点一致即可，dynamic map
/// 运行期无写入点）。
pub fn rebuild_chunk_lod(
    chunk: &ChunkSection,
    colliders: &VoxelColliderMap,
    chunk_x: i32,
    chunk_y: i32,
    chunk_z: i32,
) -> ChunkLod {
    let mut lod: ChunkLod = [None; SUBS_PER_CHUNK];
    let base_x = chunk_x << 4;
    let base_y = chunk_y << 4;
    let base_z = chunk_z << 4;

    for bx in 0..16 {
        for by in 0..16 {
            for bz in 0..16 {
                let (block_id, _voxel_state) = chunk.get_block(bx, by, bz);
                if block_id == 0 {
                    continue;
                }
                let world = IVec3::new(base_x + bx, base_y + by, base_z + bz);
                let Some(data) = colliders.get((block_id - 1) as usize, world) else {
                    continue;
                };
                if data.is_fluid {
                    continue;
                }
                let mut entry = None;
                for &(min_x, min_y, min_z, max_x, max_y, max_z) in &data.collision_boxes {
                    let ax = world.x as f64 + min_x as f64;
                    let ay = world.y as f64 + min_y as f64;
                    let az = world.z as f64 + min_z as f64;
                    let bx2 = world.x as f64 + max_x as f64;
                    let by2 = world.y as f64 + max_y as f64;
                    let bz2 = world.z as f64 + max_z as f64;
                    match entry.as_mut() {
                        None => {
                            entry = Some(SubBox {
                                min_x: ax,
                                min_y: ay,
                                min_z: az,
                                max_x: bx2,
                                max_y: by2,
                                max_z: bz2,
                            });
                        }
                        Some(sb) => sb.merge_into(ax, ay, az, bx2, by2, bz2),
                    }
                }
                if let Some(sb) = entry {
                    let idx = sub_index(bx, by, bz);
                    lod[idx] = Some(match lod[idx] {
                        None => sb,
                        Some(prev) => {
                            let mut merged = prev;
                            merged.merge_into(sb.min_x, sb.min_y, sb.min_z, sb.max_x, sb.max_y, sb.max_z);
                            merged
                        }
                    });
                }
            }
        }
    }
    lod
}

/// chunk 坐标 → 缓存 key（与 main_level_chunks 同 key 规则）。
#[inline]
pub fn chunk_key(chunk_x: i32, chunk_y: i32, chunk_z: i32) -> i64 {
    pack_section_pos(chunk_x, chunk_y, chunk_z)
}
