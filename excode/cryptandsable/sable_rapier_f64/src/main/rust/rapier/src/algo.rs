use std::cmp::min;

use crate::prec::Real;
use marten::level::OCTREE_CHUNK_SHIFT;
use crate::{IVec3, Pose3};
use crate::DVec3;
use rapier3d::math::Vec3;
use rapier3d::na::SimdComplexField;
use rayon::iter::ParallelIterator;
use rayon::prelude::{IntoParallelRefIterator, ParallelExtend};

use crate::ActiveLevelColliderInfo;
use crate::scene::{SableSceneData, pack_section_pos};

pub const DEFAULT_COLLISION_PARALLEL_CUTOFF: usize = 256;

/// Detects the collision pairs of a sable body
pub fn find_collision_pairs(
    sable_body: &ActiveLevelColliderInfo,
    other_sable_body: Option<&ActiveLevelColliderInfo>,
    isometry: &Pose3,
    prediction: Real,
    cutoff: usize,
    liquid: bool,
    sable_data: &SableSceneData,
) -> Vec<(IVec3, IVec3)> {
    struct StackObject {
        index: u32,
        depth: u32,
        min: IVec3,
    }

    let Some(octree) = &sable_body.octree else {
        panic!("No octree!")
    };

    let local_bounds_min = sable_body.local_bounds_min.unwrap();

    let center_of_mass = sable_body.center_of_mass.unwrap();

    let offset = DVec3::new(
        local_bounds_min.x as Real - center_of_mass.x,
        local_bounds_min.y as Real - center_of_mass.y,
        local_bounds_min.z as Real - center_of_mass.z,
    );
    let offset = Vec3::new(offset.x as Real, offset.y as Real, offset.z as Real);

    let offset = isometry.rotation.mul_vec3(offset.into());
    let translation = isometry.translation + offset;

    // ★ 2026-09-03 诊断（配合 3x3 扫描测试）：sable（被遍历）体 octree 为空 →
    //   find_collision_pairs 遍历不到任何叶块 → pairs=0 → 穿透。打印两体 octree
    //   空态 / log_size / 被遍历体 lbm / translation（判坐标变换是否把查询带偏）。
    //   ★ 2026-09-05 节流：每 1s 一条（SABLE_DBG=1 才输出）。
    {
        let sb_oct = sable_body.octree.as_ref();
        let ob_oct = other_sable_body.and_then(|b| b.octree.as_ref());
        let sb_empty = sb_oct.map(|o| o.is_empty());
        let ob_empty = ob_oct.map(|o| o.is_empty());
        crate::sabledbg!("find-pairs", 1000,
            "[sabledbg] find-pairs sableEmpty={:?} otherEmpty={:?} ls={:?}/{:?} lbm=({},{},{}) trans=({:.2},{:.2},{:.2})",
            sb_empty, ob_empty,
            sb_oct.map(|o| o.log_size),
            ob_oct.map(|o| o.log_size),
            local_bounds_min.x, local_bounds_min.y, local_bounds_min.z,
            translation.x, translation.y, translation.z
        );
    }

    // start with the root node
    let mut current_level = Vec::with_capacity(128);

    let com_offset: Vec3 = if let Some(other_handle) = other_sable_body {
        let com = other_handle.center_of_mass.unwrap();
        Vec3::new(com.x as Real, com.y as Real, com.z as Real)
    } else {
        Vec3::ZERO
    };

    current_level.push(StackObject {
        index: 0,
        depth: 0,
        min: IVec3::ZERO,
    });

    let mut pairs = Vec::with_capacity(16);
    // process nodes level by level to maintain some structure while parallelizing
    while !current_level.is_empty() {
        type LevelData = (Option<Vec<StackObject>>, Option<Vec<(IVec3, IVec3)>>);
        let mut next_level_data = Vec::<LevelData>::with_capacity(8);

        let do_level_parallel = current_level.len() >= cutoff;

        let process_stack_object = |entry: &StackObject| -> LevelData {
            let node = *unsafe { octree.buffer.get_unchecked(entry.index as usize) };
            let node_size = 1 << (octree.log_size as u32 - entry.depth);

            // Calculate the center and radius for this node
            let node_center = crate::prec::to_vec3(entry.min.as_dvec3() + node_size as f64 / 2.0);
            let transformed_center = isometry.rotation.mul_vec3(node_center) + translation;
            // ★ 2026-09-03 穿透修复：配对半径必须覆盖【相邻】（恰好接触、零间隙）的方块。
            //   原半径 = node_size/2*sqrt(3) + prediction：叶节点(1 格) = 0.866+0.05=0.916
            //   < 相邻方块中心距(1.0) → 结构底块与地面顶块【恰好贴合时】不配对 → 无接触
            //   → 结构"无碰撞就下掉"（只有一开始轻微嵌入重叠时才撑住）。
            //   固定 +1.0 格 margin：叶节点半径≥1.916，必纳入相邻格中心 → 静止贴合也能撑住。
            let radius = node_size as Real / 2.0 * 1.7321 + prediction + 1.0;

            let (has_any_intersections, blocks_opt) = get_overlapping_nodes(
                other_sable_body,
                com_offset,
                transformed_center.into(),
                radius,
                sable_data,
                node >= 0,
                liquid,
            );

            if !has_any_intersections {
                return (None, None);
            }

            // leaf node - add collision pairs
            if node < 0 {
                let mut local_pairs = Vec::new();
                for static_block in blocks_opt.unwrap().iter() {
                    local_pairs.push((*static_block, entry.min + local_bounds_min));
                }

                return (None, Some(local_pairs));
            }

            if node > 0 {
                let mut local_next_level = Vec::with_capacity(8);

                for i in 0..8 {
                    local_next_level.push(StackObject {
                        index: (node + i) as u32,
                        depth: entry.depth + 1,
                        min: entry.min
                            + crate::prec::ivec3(
                                ((i & 1) * node_size / 2) as i64,
                                (((i >> 1) & 1) * node_size / 2) as i64,
                                (((i >> 2) & 1) * node_size / 2) as i64,
                            ),
                    });
                }

                (Some(local_next_level), None)
            } else {
                (None, None)
            }
        };

        if do_level_parallel {
            next_level_data.par_extend(current_level.par_iter().map(process_stack_object))
        } else {
            next_level_data.extend(current_level.iter().map(process_stack_object))
        }

        let (a_parts, b_parts): (Vec<_>, Vec<_>) = next_level_data.into_iter().unzip();

        // filter out none's and add them
        for local_pairs in b_parts.into_iter().flatten() {
            pairs.extend(local_pairs);
        }

        current_level = a_parts.into_iter().flatten().flatten().collect();
    }

    pairs
}

fn get_overlapping_nodes(
    other_handle: Option<&ActiveLevelColliderInfo>,
    com_offset: DVec3,
    pos: Vec3,
    dist: Real,
    sable_data: &SableSceneData,
    cancel_early: bool,
    liquid: bool,
) -> (bool, Option<Vec<IVec3>>) {
    // biggest power of two that doesn't go over radius
    let log2 = ((dist * 2.0).simd_ln() / 2.0.simd_ln()).floor() as i32;

    let log2 = if let Some(other_handle) = other_handle {
        let Some(oct) = &other_handle.octree else {
            panic!("No octree!")
        };
        min(log2, oct.log_size)
    } else {
        min(log2, OCTREE_CHUNK_SHIFT)
    };

    let min_block_pos = crate::prec::floor_to_ivec((pos - dist) + com_offset);
    let max_block_pos = crate::prec::floor_to_ivec((pos + dist) + com_offset);

    if let Some(other_handle) = other_handle {
        let other_min = other_handle.local_bounds_min.unwrap();

        let min_pos = ((min_block_pos - other_min) >> log2).map(|x| x.max(0));
        let max_pos = (max_block_pos - other_min) >> log2;

        let Some(oct) = &other_handle.octree else {
            panic!("No octree!")
        };

        let mut blocks = if cancel_early {
            None
        } else {
            Some(Vec::with_capacity(16))
        };

        for x in min_pos.x..=max_pos.x {
            for y in min_pos.y..=max_pos.y {
                for z in min_pos.z..=max_pos.z {
                    if oct.query(
                        (x << log2) as i32,
                        (y << log2) as i32,
                        (z << log2) as i32,
                        log2,
                    ) > -2
                    {
                        if cancel_early {
                            return (true, None);
                        } else {
                            blocks.as_mut().unwrap().push(IVec3::new(
                                (x << log2) + other_min.x,
                                (y << log2) + other_min.y,
                                (z << log2) + other_min.z,
                            ));
                        }
                    }
                }
            }
        }

        if cancel_early {
            return (false, None);
        } else {
            return (!blocks.as_ref().unwrap().is_empty(), blocks);
        }
    }

    // find all the octrees
    let min_octree_pos = min_block_pos >> OCTREE_CHUNK_SHIFT;
    let max_octree_pos = max_block_pos >> OCTREE_CHUNK_SHIFT;

    let mut blocks = if cancel_early {
        None
    } else {
        Some(Vec::with_capacity(8))
    };
    for ox in min_octree_pos.x..=max_octree_pos.x {
        for oy in min_octree_pos.y..=max_octree_pos.y {
            for oz in min_octree_pos.z..=max_octree_pos.z {
                let chunk = sable_data
                    .octree_chunks
                    .get(&pack_section_pos(ox as i32, oy as i32, oz as i32));
                let Some(chunk) = chunk else {
                    continue;
                };

                let min_x = min_block_pos.x >> log2;
                let min_y = min_block_pos.y >> log2;
                let min_z = min_block_pos.z >> log2;
                let max_x = max_block_pos.x >> log2;
                let max_y = max_block_pos.y >> log2;
                let max_z = max_block_pos.z >> log2;
                let chunk_octree = if liquid {
                    &chunk.liquid_octree
                } else {
                    &chunk.octree
                };

                if chunk_octree.is_empty() {
                    continue;
                }

                for x in min_x..=max_x {
                    for y in min_y..=max_y {
                        for z in min_z..=max_z {
                            if chunk_octree.query(
                                ((x << log2) - (ox << OCTREE_CHUNK_SHIFT)) as i32,
                                ((y << log2) - (oy << OCTREE_CHUNK_SHIFT)) as i32,
                                ((z << log2) - (oz << OCTREE_CHUNK_SHIFT)) as i32,
                                log2,
                            ) > -2
                            {
                                if cancel_early {
                                    return (true, None);
                                } else {
                                    blocks.as_mut().unwrap().push(IVec3::new(x, y, z) << log2);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if cancel_early {
        (false, None)
    } else {
        (!blocks.as_ref().unwrap().is_empty(), blocks)
    }
}
