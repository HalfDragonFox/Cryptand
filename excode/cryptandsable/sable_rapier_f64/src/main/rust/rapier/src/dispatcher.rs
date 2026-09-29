use crate::collider::LevelCollider;
use log::info;
use rapier3d::geometry::{ContactManifoldData, Shape};
use crate::{IVec3, Pose3};
use crate::DVec3;
use rapier3d::math::Vec3;
use rapier3d::parry::query::details::{NormalConstraints, contact_manifold_cuboid_cuboid_shapes};
use rapier3d::parry::query::{
    ClosestPoints, Contact, ContactManifold, ContactManifoldsWorkspace, DefaultQueryDispatcher,
    NonlinearRigidMotion, PersistentQueryDispatcher, QueryDispatcher, ShapeCastHit,
    ShapeCastOptions, Unsupported,
};
use rapier3d::prelude::ShapeType::Custom;
use rapier3d::prelude::{Aabb, Real};

use crate::algo::find_collision_pairs;
use crate::scene::{ChunkAccess, LevelColliderID, SableManifoldInfo, SableManifoldInfoMap, SableSceneData};
use crate::{ActiveLevelColliderInfo, PhysicsState};
use marten::level::VoxelPhysicsState::{Edge, Face, Interior};
use marten::level::{ChunkSection, NEEDS_HOOKS_USER_DATA, VoxelPhysicsState};
use std::sync::atomic::Ordering;
use std::sync::{Arc, RwLock};

/// The distance we scale collision points local to the box collider for before we check interior collisions
/// This helps avoid a missed interior collision when points are slightly outside of their voxel on an axis
/// not aligned with their normal. Example: A horizontal interior collision with a point 0.5001 above the
/// block center.
const INTERIOR_COLLISION_SCALE_FACTOR: Real = 0.99;

/// The distance at which we offset the normal of a collision point to check if it is inside a voxel collider
/// to rule it as an interior collision
const INTERIOR_COLLISION_CHECK_DISTANCE: Real = 0.015;

#[derive(Clone)]
pub struct SableDispatcher {
    pub sable_data: Arc<RwLock<SableSceneData>>,
    pub manifold_info_map: Arc<SableManifoldInfoMap>,
}

impl SableDispatcher {
    /// Computes the local, inclusive block bounds of a global aabb with inflation
    #[allow(clippy::cast_possible_truncation)]
    #[allow(unused)]
    fn get_local_block_bounds(mut local_aabb: Aabb, inflation: Real) -> (IVec3, IVec3) {
        // Inflate the aabb by the prediction distance
        local_aabb.maxs += Vec3::splat(inflation);
        local_aabb.mins -= Vec3::splat(inflation);

        let local_min = crate::prec::ivec3(
            local_aabb.mins.x.floor() as i64,
            local_aabb.mins.y.floor() as i64,
            local_aabb.mins.z.floor() as i64,
        );

        let local_max = crate::prec::ivec3(
            local_aabb.maxs.x.floor() as i64,
            local_aabb.maxs.y.floor() as i64,
            local_aabb.maxs.z.floor() as i64,
        );

        (local_min, local_max)
    }
}

impl QueryDispatcher for SableDispatcher {
    fn intersection_test(
        &self,
        _pos12: &Pose3,
        g1: &dyn Shape,
        g2: &dyn Shape,
    ) -> Result<bool, Unsupported> {
        info!("intersect {:?} <-> {:?}", g1.shape_type(), g2.shape_type());
        Err(Unsupported)
    }

    fn distance(
        &self,
        _pos12: &Pose3,
        g1: &dyn Shape,
        g2: &dyn Shape,
    ) -> Result<Real, Unsupported> {
        info!("distance {:?} <-> {:?}", g1.shape_type(), g2.shape_type());
        Err(Unsupported)
    }

    fn contact(
        &self,
        _pos12: &Pose3,
        g1: &dyn Shape,
        g2: &dyn Shape,
        _prediction: Real,
    ) -> Result<Option<Contact>, Unsupported> {
        info!("contact {:?} <-> {:?}", g1.shape_type(), g2.shape_type());
        Err(Unsupported)
    }

    fn closest_points(
        &self,
        _pos12: &Pose3,
        g1: &dyn Shape,
        g2: &dyn Shape,
        _max_dist: Real,
    ) -> Result<ClosestPoints, Unsupported> {
        info!(
            "closest points {:?} <-> {:?}",
            g1.shape_type(),
            g2.shape_type()
        );
        Err(Unsupported)
    }

    fn cast_shapes(
        &self,
        _pos12: &Pose3,
        _local_vel12: Vec3,
        _g1: &dyn Shape,
        _g2: &dyn Shape,
        _options: ShapeCastOptions,
    ) -> Result<Option<ShapeCastHit>, Unsupported> {
        Err(Unsupported)
    }

    fn cast_shapes_nonlinear(
        &self,
        _motion1: &NonlinearRigidMotion,
        _g1: &dyn Shape,
        _motion2: &NonlinearRigidMotion,
        _g2: &dyn Shape,
        _start_time: Real,
        _end_time: Real,
        _stop_at_penetration: bool,
    ) -> Result<Option<ShapeCastHit>, Unsupported> {
        Err(Unsupported)
    }
}

impl<ContactData> PersistentQueryDispatcher<ContactManifoldData, ContactData> for SableDispatcher
where
    ContactData: Default + Copy,
{
    fn contact_manifolds(
        &self,
        pos12: &Pose3,
        g1: &dyn Shape,
        g2: &dyn Shape,
        prediction: Real,
        manifolds: &mut Vec<ContactManifold<ContactManifoldData, ContactData>>,
        _workspace: &mut Option<ContactManifoldsWorkspace>,
    ) -> Result<(), Unsupported> {
        crate::sabledbg!("narrowphase", 1000,
            "[sabledbg] narrowphase contact_manifolds entered g1_type={:?} g2_type={:?}",
            g1.shape_type(), g2.shape_type()
        );
        if g1.shape_type() != Custom && g2.shape_type() != Custom {
            return Err(Unsupported);
        }

        if g1.shape_type() == Custom && g2.shape_type() != Custom {
            self.static_world_vs_collider::<ContactData>(
                pos12,
                g1.as_shape::<LevelCollider>().unwrap(),
                g2,
                prediction,
                manifolds,
                false,
            );
        } else if g1.shape_type() != Custom && g2.shape_type() == Custom {
            self.static_world_vs_collider::<ContactData>(
                &pos12.inverse(),
                g2.as_shape::<LevelCollider>().unwrap(),
                g1,
                prediction,
                manifolds,
                true,
            );
        } else {
            assert_eq!(g1.shape_type(), Custom);
            assert_eq!(g2.shape_type(), Custom);

            let g1 = g1.as_shape::<LevelCollider>().unwrap();
            let g2 = g2.as_shape::<LevelCollider>().unwrap();
            crate::sabledbg!("custom-pair", 1000,
                "[sabledbg] contact custom-custom g1(id={:?},static={}) g2(id={:?},static={})",
                g1.id, g1.is_static, g2.id, g2.is_static
            );

            if g1.is_static && !g2.is_static {
                self.world_vs_world::<ContactData>(pos12, g1, g2, prediction, manifolds, false);
            } else if !g1.is_static && g2.is_static {
                // ★ 2026-09-02 修复「结构无限下落/穿地」：动态结构在前、静态世界(ground)在后时，
                //   原实现没有该分支 → 静默返回 0 manifold → 结构永远拿不到地面接触。
                //   补镜像分支：交换两体并把 pos12 取逆（得到世界→结构的变换）复用 world_vs_world。
                self.world_vs_world::<ContactData>(
                    &pos12.inverse(),
                    g2,
                    g1,
                    prediction,
                    manifolds,
                    false,
                );
            } else if !g1.is_static && !g2.is_static {
                // ★ 2026-09-06 防御：level_colliders 缺 id / bounds 缺失（重建空窗期
                //   或跨空间误引用）→ 跳过该对（返回空 manifold）而非 panic（panic
                //   会中止进程）。配对的 body 在创建链会由 setLocalBounds 补齐，
                //   随后 step 恢复正常。
                let swap = {
                    let sable_data = self.sable_data.read().unwrap();
                    let body_1 = match g1.id.and_then(|id| sable_data.level_colliders.get(&(id as LevelColliderID))) {
                        Some(b) => b,
                        None => return Ok(()),
                    };
                    let body_2 = match g2.id.and_then(|id| sable_data.level_colliders.get(&(id as LevelColliderID))) {
                        Some(b) => b,
                        None => return Ok(()),
                    };

                    let (extents_1, extents_2) = match (body_1.local_bounds_max, body_1.local_bounds_min,
                                                        body_2.local_bounds_max, body_2.local_bounds_min) {
                        (Some(b1x), Some(b1n), Some(b2x), Some(b2n)) => (b1x - b1n + IVec3::ONE, b2x - b2n + IVec3::ONE),
                        _ => return Ok(()),
                    };

                    let volume_1 = extents_1.x * extents_1.y * extents_1.z;
                    let volume_2 = extents_2.x * extents_2.y * extents_2.z;

                    // Swap the bodies so we're always doing the least amount of work possible for collision detection
                    volume_1 < volume_2
                };

                if swap {
                    self.world_vs_world::<ContactData>(
                        &pos12.inverse(),
                        g2,
                        g1,
                        prediction,
                        manifolds,
                        true,
                    );
                } else {
                    self.world_vs_world::<ContactData>(pos12, g1, g2, prediction, manifolds, false);
                }
            }
        }

        Ok(())
    }

    fn contact_manifold_convex_convex(
        &self,
        _pos12: &Pose3,
        _g1: &dyn Shape,
        _g2: &dyn Shape,
        _normal_constraints1: Option<&dyn NormalConstraints>,
        _normal_constraints2: Option<&dyn NormalConstraints>,
        _prediction: Real,
        _manifold: &mut ContactManifold<ContactManifoldData, ContactData>,
    ) -> Result<(), Unsupported> {
        info!(
            "manifolds convex convex {:?} <-> {:?}",
            _g1.shape_type(),
            _g2.shape_type()
        );

        Err(Unsupported)
    }
}

impl SableDispatcher {
    fn static_world_vs_collider<ContactData: Default + Copy>(
        &self,
        pos12: &Pose3,
        g1: &LevelCollider,
        g2: &dyn Shape,
        prediction: Real,
        manifolds: &mut Vec<ContactManifold<ContactManifoldData, ContactData>>,
        swap: bool,
    ) {
        let physics_state = crate::get_physics_state();
        let sable_data = self.sable_data.read().unwrap();

        let collider_info = g1
            .id
            .map(|id| &sable_data.level_colliders[&(id as LevelColliderID)]);
        let center_of_mass_1 = collider_info.map_or(DVec3::ZERO, |b| b.center_of_mass.unwrap());

        let mut local_aabb = g2.compute_aabb(pos12);

        let margin: Real = 0.1;
        local_aabb.maxs += Vec3::splat(prediction + margin);
        local_aabb.mins -= Vec3::splat(prediction + margin);
        let local_aabb =
            Self::adjust_aabb_for_body(local_aabb, collider_info, center_of_mass_1, prediction);
        let (local_min, local_max) =
            Self::calculate_local_bounds(local_aabb, center_of_mass_1, prediction);

        let mut manifold_index = 0;

        let chunk_access: &dyn ChunkAccess = if let Some(info) = collider_info
            && info.has_own_chunks()
        {
            info
        } else {
            &*sable_data
        };

        for x in local_min.x..=local_max.x {
            for y in local_min.y..=local_max.y {
                for z in local_min.z..=local_max.z {
                    let Some(chunk) = chunk_access.get_chunk(
                        (x >> 4) as i32,
                        (y >> 4) as i32,
                        (z >> 4) as i32,
                    ) else {
                        // chunk doesn't exist
                        continue;
                    };
                    let (block_id, _voxel_collider_state) = chunk.get_block(
                        (x & 15) as i32,
                        (y & 15) as i32,
                        (z & 15) as i32,
                    );

                    // block id's are unsigned, and offset by 1 to allow for a single "empty" at 0
                    if block_id == 0 {
                        continue;
                    }

                    let voxel_collider_data = &physics_state
                        .voxel_collider_map
                        .get((block_id - 1) as usize, IVec3::new(x, y, z));

                    if voxel_collider_data.is_none() {
                        continue;
                    }

                    let Some(voxel_collider_data) = &voxel_collider_data else {
                        unreachable!()
                    };

                    if voxel_collider_data.is_fluid {
                        continue;
                    }

                    for (min_x, min_y, min_z, max_x, max_y, max_z) in
                        &voxel_collider_data.collision_boxes
                    {
                        if manifolds.len() <= manifold_index {
                            manifolds.push(ContactManifold::new());
                        }

                        let center = DVec3::new(
                            ((min_x + max_x) / 2.0) as Real,
                            ((min_y + max_y) / 2.0) as Real,
                            ((min_z + max_z) / 2.0) as Real,
                        ) + DVec3::new(x as Real, y as Real, z as Real)
                            - center_of_mass_1;
                        let center =
                            Vec3::new(center.x as Real, center.y as Real, center.z as Real);

                        let half_extents = Vec3::new(
                            (max_x - min_x) as Real / 2.0,
                            (max_y - min_y) as Real / 2.0,
                            (max_z - min_z) as Real / 2.0,
                        );

                        // Translate to match the center of the current block
                        let mut block_isometry = *pos12;
                        block_isometry.translation -= center;

                        if !swap {
                            DefaultQueryDispatcher
                                .contact_manifold_convex_convex(
                                    &block_isometry,
                                    &rapier3d::parry::shape::Cuboid::new(Vec3::new(
                                        half_extents.x,
                                        half_extents.y,
                                        half_extents.z,
                                    )),
                                    g2,
                                    None,
                                    None,
                                    prediction,
                                    &mut manifolds[manifold_index],
                                )
                                .expect("uh oh");
                        } else {
                            DefaultQueryDispatcher
                                .contact_manifold_convex_convex(
                                    &block_isometry.inverse(),
                                    g2,
                                    &rapier3d::parry::shape::Cuboid::new(Vec3::new(
                                        half_extents.x,
                                        half_extents.y,
                                        half_extents.z,
                                    )),
                                    None,
                                    None,
                                    prediction,
                                    &mut manifolds[manifold_index],
                                )
                                .expect("uh oh");
                        }

                        if !manifolds[manifold_index].points.is_empty() {
                            let index = self
                                .manifold_info_map
                                .counter
                                .fetch_add(1, Ordering::Relaxed);

                            let block_pos = IVec3::new(x, y, z);
                            self.manifold_info_map.list.insert(
                                index,
                                if swap {
                                    SableManifoldInfo {
                                        pos_a: IVec3::ZERO,
                                        pos_b: block_pos,
                                        col_a: 0,
                                        col_b: block_id as usize,
                                    }
                                } else {
                                    SableManifoldInfo {
                                        pos_a: block_pos,
                                        pos_b: IVec3::ZERO,
                                        col_a: block_id as usize,
                                        col_b: 0,
                                    }
                                },
                            );

                            manifolds[manifold_index].data.user_data =
                                voxel_collider_data.get_user_data() | ((index << 1) as u32);
                        } else {
                            manifolds[manifold_index].data.user_data =
                                voxel_collider_data.get_user_data();
                        }

                        if collider_info.is_some()
                            && let Some(_velocities) = collider_info.unwrap().fake_velocities
                        {
                            manifolds[manifold_index].data.user_data |= NEEDS_HOOKS_USER_DATA;
                        }

                        for point in &mut manifolds[manifold_index].points {
                            let diff = Vec3::new(center.x, center.y, center.z);
                            match swap {
                                true => point.local_p2 -= diff,
                                false => point.local_p1 += diff,
                            }
                        }

                        manifold_index += 1;
                    }
                }
            }
        }

        if manifolds.len() > manifold_index {
            manifolds.truncate(manifold_index);
        }
    }

    fn world_vs_world<ContactData: Default + Copy>(
        &self,
        pos12: &Pose3,
        g1: &LevelCollider,
        g2: &LevelCollider,
        prediction: Real,
        manifolds: &mut Vec<ContactManifold<ContactManifoldData, ContactData>>,
        swap: bool,
    ) {
        let physics_state = crate::get_physics_state();
        let sable_data = self.sable_data.read().unwrap();

        let collider_info_1 = g1
            .id
            .map(|id| &sable_data.level_colliders[&(id as LevelColliderID)]);
        let collider_info_2 = &sable_data.level_colliders[&(g2.id.unwrap() as LevelColliderID)];
        let center_of_mass_1 =
            collider_info_1.and_then(|b| b.center_of_mass).unwrap_or(DVec3::ZERO);
        let center_of_mass_2 = collider_info_2.center_of_mass.unwrap();

        let chunk_access_1: &dyn ChunkAccess = if let Some(info) = collider_info_1
            && info.has_own_chunks()
        {
            info
        } else {
            &*sable_data
        };

        let chunk_access_2: &dyn ChunkAccess = if collider_info_2.has_own_chunks() {
            collider_info_2
        } else {
            &*sable_data
        };

        // let local_aabb = g2.compute_aabb(&pos12);
        // let local_aabb = Self::adjust_aabb_for_body(local_aabb, body_1, center_of_mass_1, prediction);
        // let (local_min, local_max) = Self::calculate_local_bounds(local_aabb, center_of_mass_1, prediction);

        let mut manifold_index = 0;
        // ★ 2026-09-03 调统计桩：定位 pairs 在哪个 continue 分支被丢弃
        //   0=pairs 1=chunk1_miss 2=blk1_zero 3=coll1_miss 4=chunk2_miss
        //   5=blk2_zero 6=ignored 7=coll2_miss
        let mut dbg: [usize; 8] = [0; 8];

        let pairs = find_collision_pairs(
            collider_info_2,
            collider_info_1,
            pos12,
            prediction,
            256,
            false,
            &sable_data,
        );
        crate::sabledbg!("wvw", 1000,
            "[sabledbg] world_vs_world g1(id={:?}) g2(id={:?}) pairs={}",
            g1.id, g2.id, pairs.len()
        );
        dbg[0] = pairs.len();
        crate::sabledbg!("wvw-detail", 1500,
            "[sabledbg] wvw-detail g1(id={:?}) g2(id={:?}) own1={:?} own2={} lbm1={:?} lbm2={:?} com1={:?} com2={:?} st=({},{},{}) dy=({},{},{})",
            g1.id, g2.id,
            collider_info_1.map(|i| i.has_own_chunks()),
            collider_info_2.has_own_chunks(),
            collider_info_1.map(|i| i.local_bounds_min),
            collider_info_2.local_bounds_min,
            collider_info_1.map(|i| i.center_of_mass),
            collider_info_2.center_of_mass,
            pairs.first().map(|p| p.0.x).unwrap_or(0),
            pairs.first().map(|p| p.0.y).unwrap_or(0),
            pairs.first().map(|p| p.0.z).unwrap_or(0),
            pairs.first().map(|p| p.1.x).unwrap_or(0),
            pairs.first().map(|p| p.1.y).unwrap_or(0),
            pairs.first().map(|p| p.1.z).unwrap_or(0)
        );

        for (static_pos, dynamic_pos) in pairs.iter() {
            let static_x = static_pos.x;
            let static_y = static_pos.y;
            let static_z = static_pos.z;

            let other_bx = dynamic_pos.x;
            let other_by = dynamic_pos.y;
            let other_bz = dynamic_pos.z;

            let Some(chunk) = chunk_access_1
                .get_chunk(
                    (static_x >> 4) as i32,
                    (static_y >> 4) as i32,
                    (static_z >> 4) as i32,
                )
            else {
                dbg[1] += 1;   // chunk1 miss
                crate::sabledbg!("wvw-c1-miss", 1000,
                    "[sabledbg] wvw-c1-miss st=({},{},{}) sec=({},{},{}) mainlen={}",
                    static_x, static_y, static_z,
                    (static_x >> 4) as i32,
                    (static_y >> 4) as i32,
                    (static_z >> 4) as i32,
                    sable_data.main_level_chunks.len()
                );
                continue;
            };
            let (block_id, voxel_collider_state) = chunk.get_block(
                (static_x & 15) as i32,
                (static_y & 15) as i32,
                (static_z & 15) as i32,
            );

            // block id's are unsigned, and offset by 1 to allow for a single "empty" at 0
            if block_id == 0 {
                dbg[2] += 1;   // blk1 zero
                continue;
            }

            let voxel_collider_data = &physics_state.voxel_collider_map.get(
                (block_id - 1) as usize,
                IVec3::new(static_x, static_y, static_z),
            );

            let Some(voxel_collider_data) = &voxel_collider_data else {
                dbg[3] += 1;   // coll1 miss
                continue;
            };

            for (min_x, min_y, min_z, max_x, max_y, max_z) in &voxel_collider_data.collision_boxes {
                if manifolds.len() <= manifold_index {
                    manifolds.push(ContactManifold::new());
                }

                let center = DVec3::new(
                    ((min_x + max_x) / 2.0) as Real,
                    ((min_y + max_y) / 2.0) as Real,
                    ((min_z + max_z) / 2.0) as Real,
                ) + DVec3::new(static_x as Real, static_y as Real, static_z as Real)
                    - center_of_mass_1;
                let center = Vec3::new(center.x as Real, center.y as Real, center.z as Real);

                let half_extents = Vec3::new(
                    (max_x - min_x) as Real / 2.0,
                    (max_y - min_y) as Real / 2.0,
                    (max_z - min_z) as Real / 2.0,
                );

                // Translate to match the center of the current block
                let mut block_isometry = *pos12;
                block_isometry.translation -= center;

                let Some(other_chunk) = chunk_access_2
                    .get_chunk(
                        (other_bx >> 4) as i32,
                        (other_by >> 4) as i32,
                        (other_bz >> 4) as i32,
                    )
                else {
                    dbg[4] += 1;   // chunk2 miss
                    continue;
                };
                let (other_block_id, other_voxel_collider_state) = other_chunk.get_block(
                    (other_bx & 15) as i32,
                    (other_by & 15) as i32,
                    (other_bz & 15) as i32,
                );

                // block id's are unsigned, and offset by 1 to allow for a single "empty" at 0
                if other_block_id == 0 {
                    dbg[5] += 1;   // blk2 zero
                    continue;
                }

                if Self::can_ignore_collision(voxel_collider_state, other_voxel_collider_state) {
                    dbg[6] += 1;   // ignored
                    crate::sabledbg!("voxel-ignored", 1000,
                        "[sabledbg] voxel pair IGNORED at ({},{},{}) state1={:?} state2={:?} (blk1={} blk2={})",
                        static_x, static_y, static_z,
                        voxel_collider_state, other_voxel_collider_state,
                        block_id, other_block_id
                    );
                    continue;
                }

                let other_voxel_collider_data = &physics_state.voxel_collider_map.get(
                    (other_block_id - 1) as usize,
                    IVec3::new(other_bx, other_by, other_bz),
                );

                let Some(other_voxel_collider_data) = &other_voxel_collider_data else {
                    dbg[7] += 1;   // coll2 miss
                    continue;
                };

                for (
                    other_min_x,
                    other_min_y,
                    other_min_z,
                    other_max_x,
                    other_max_y,
                    other_max_z,
                ) in &other_voxel_collider_data.collision_boxes
                {
                    if manifolds.len() <= manifold_index {
                        manifolds.push(ContactManifold::new());
                    }

                    let other_center =
                        DVec3::new(
                            ((other_min_x + other_max_x) / 2.0) as Real,
                            ((other_min_y + other_max_y) / 2.0) as Real,
                            ((other_min_z + other_max_z) / 2.0) as Real,
                        ) + DVec3::new(other_bx as Real, other_by as Real, other_bz as Real)
                            - center_of_mass_2;
                    let other_center = Vec3::new(
                        other_center.x as Real,
                        other_center.y as Real,
                        other_center.z as Real,
                    );

                    let other_half_extents = Vec3::new(
                        (other_max_x - other_min_x) as Real / 2.0,
                        (other_max_y - other_min_y) as Real / 2.0,
                        (other_max_z - other_min_z) as Real / 2.0,
                    );

                    // combine block isometries
                    let mut combined_block_isometry = block_isometry;

                    let transformed = combined_block_isometry.rotation.mul_vec3(other_center);

                    combined_block_isometry.translation += transformed;

                    let mut new_manifold: ContactManifold<ContactManifoldData, ContactData> =
                        ContactManifold::new();
                    contact_manifold_cuboid_cuboid_shapes(
                        &combined_block_isometry,
                        &rapier3d::parry::shape::Cuboid::new(half_extents),
                        &rapier3d::parry::shape::Cuboid::new(other_half_extents),
                        prediction,
                        &mut new_manifold,
                    );

                    if !is_interior_collision(
                        chunk_access_1,
                        chunk_access_2,
                        collider_info_1,
                        collider_info_2,
                        g1.id.is_some() && g1.id == g2.id,
                        IVec3::new(static_x, static_y, static_z),
                        IVec3::new(other_bx, other_by, other_bz),
                        center,
                        other_center,
                        center_of_mass_1,
                        center_of_mass_2,
                        &mut new_manifold,
                    ) {
                        let index = self
                            .manifold_info_map
                            .counter
                            .fetch_add(1, Ordering::Relaxed);

                        self.manifold_info_map.list.insert(
                            index,
                            if swap {
                                SableManifoldInfo {
                                    pos_a: IVec3::new(other_bx, other_by, other_bz),
                                    pos_b: IVec3::new(static_x, static_y, static_z),
                                    col_a: other_block_id as usize,
                                    col_b: block_id as usize,
                                }
                            } else {
                                SableManifoldInfo {
                                    pos_a: IVec3::new(static_x, static_y, static_z),
                                    pos_b: IVec3::new(other_bx, other_by, other_bz),
                                    col_a: block_id as usize,
                                    col_b: other_block_id as usize,
                                }
                            },
                        );

                        new_manifold.data.user_data = voxel_collider_data.get_user_data()
                            | other_voxel_collider_data.get_user_data()
                            | ((index << 1) as u32);

                        if let Some(_velocities) = collider_info_2.fake_velocities {
                            new_manifold.data.user_data |= NEEDS_HOOKS_USER_DATA;
                        }
                        if let Some(info) = collider_info_1
                            && info.fake_velocities.is_some()
                        {
                            new_manifold.data.user_data |= NEEDS_HOOKS_USER_DATA;
                        }

                        manifolds[manifold_index] = new_manifold;

                        for point in &mut manifolds[manifold_index].points {
                            point.local_p1 += center;
                            point.local_p2 += other_center;
                        }

                        manifold_index += 1;
                    }
                }
            }
        }

        // swap bodies in the manifolds
        if swap {
            for manifold in manifolds.iter_mut() {
                for point in &mut manifold.points {
                    // swap positions
                    std::mem::swap(&mut point.local_p1, &mut point.local_p2);
                }

                // swap normals
                std::mem::swap(&mut manifold.local_n1, &mut manifold.local_n2);
            }
        }

        if manifolds.len() > manifold_index {
            manifolds.truncate(manifold_index);
        }
        crate::sabledbg!("wvw-summary", 1000,
            "[sabledbg] wvw-summary g1={:?} g2={:?} pairs={} c1miss={} b1zero={} col1miss={} c2miss={} b2zero={} ignored={} col2miss={} manifolds={}",
            g1.id, g2.id, dbg[0], dbg[1], dbg[2], dbg[3], dbg[4], dbg[5], dbg[6], dbg[7],
            manifold_index
        );
        crate::sabledbg!("wvw-manifolds", 1500,
            "[sabledbg] wvw-manifolds g1={:?} g2={:?} count={} prediction={}",
            g1.id, g2.id, manifolds.len(), prediction
        );
    }

    /// Adjusts the AABB for a given body
    #[inline(always)]
    fn adjust_aabb_for_body(
        mut local_aabb: Aabb,
        body: Option<&ActiveLevelColliderInfo>,
        center_of_mass: DVec3,
        prediction: Real,
    ) -> Aabb {
        if let Some(body) = body {
            let local_bounds_min = body.local_bounds_min.unwrap();
            let local_bounds_max = body.local_bounds_max.unwrap();

            let body_aabb = Aabb::new(
                crate::prec::to_vec3(local_bounds_min.as_dvec3()) - center_of_mass - prediction,
                crate::prec::to_vec3(local_bounds_max.as_dvec3()) - center_of_mass - prediction,
            );

            local_aabb = local_aabb.intersection(&body_aabb).unwrap_or(local_aabb);
        }

        local_aabb
    }

    /// Calculates local bounds based on AABB and prediction
    #[inline(always)]
    fn calculate_local_bounds(
        aabb: Aabb,
        center_of_mass: DVec3,
        prediction: Real,
    ) -> (IVec3, IVec3) {
        let mins = aabb.mins + center_of_mass - prediction;
        let maxs = aabb.maxs + center_of_mass + prediction;

        let local_min = crate::prec::floor_to_ivec(mins);
        let local_max = crate::prec::floor_to_ivec(maxs);

        (local_min, local_max)
    }

    #[inline(always)]
    #[must_use]
    fn can_ignore_collision(
        voxel_collider_state: VoxelPhysicsState,
        other_voxel_collider_state: VoxelPhysicsState,
    ) -> bool {
        (other_voxel_collider_state == voxel_collider_state && voxel_collider_state == Face)
            || voxel_collider_state == Interior
            || other_voxel_collider_state == Interior
            || (voxel_collider_state == Edge && other_voxel_collider_state == Face)
            || (voxel_collider_state == Face && other_voxel_collider_state == Edge)
    }
}

fn get_block_pos(vec: DVec3) -> IVec3 {
    crate::prec::ivec3(
        vec.x.floor() as i64,
        vec.y.floor() as i64,
        vec.z.floor() as i64,
    )
}

fn is_interior_collision<ManifoldData: Default + Clone, ContactData: Default + Copy>(
    chunk_access_1: &dyn ChunkAccess,
    chunk_access_2: &dyn ChunkAccess,
    collider_info_1: Option<&ActiveLevelColliderInfo>,
    collider_info_2: &ActiveLevelColliderInfo,
    same_body: bool,
    block_a: IVec3,
    block_b: IVec3,
    center: Vec3,
    other_center: Vec3,
    center_of_mass_1: DVec3,
    center_of_mass_2: DVec3,
    manifold: &mut ContactManifold<ManifoldData, ContactData>,
) -> bool {
    let physics_state = crate::get_physics_state();

    // ★ 2026-09-03 穿透修复：is_interior_collision 官方语义 = 【同一结构自身】的
    //   内部面去重（防止结构内部面/被挖空区域的自身接触）。
    //   本架构是【多 body】碰撞（结构 vs 陪体 / 结构 vs 结构 / 结构 vs 世界）：
    //   两个不同 body 的表面接触点落在对方实心块内是【正常表面支撑】，若按官方
    //   "点在体内即删" 会把静止接触点全删光 → 0 支撑 → 结构穿透。
    //   → 只有【同一 body 自身】(g1.id==g2.id) 才删内部点；不同 body 保留全部接触点。
    if !same_body {
        return false;
    }

    manifold.points.retain(|point| {
        if collider_info_1.is_none()
            || (collider_info_1.unwrap().local_bounds_min.unwrap()
                != collider_info_1.unwrap().local_bounds_max.unwrap())
        {
            let world_p1 = (point.local_p1 * 0.997 + center) + center_of_mass_1;
            let normal1 = manifold.local_n1;

            let displaced_p1 = world_p1 + normal1 * 0.01;

            let inside1 = is_inside_voxel_collider(chunk_access_1, &physics_state, block_a, displaced_p1);
            if inside1 {
                // ★ 2026-09-03 打点：块1 内判定删除（节流 1s）
                crate::sabledbg!("interior-rm1", 1000,
                    "[sabledbg] interior-rm1 block_a=({},{},{}) wp1=({:.3},{:.3},{:.3})",
                    block_a.x, block_a.y, block_a.z, world_p1.x, world_p1.y, world_p1.z
                );
                return false;
            }
        }

        if collider_info_2.local_bounds_min.unwrap() != collider_info_2.local_bounds_max.unwrap() {
            let normal2 = manifold.local_n2;

            // we have to "pull in the points" a tiny bit incase they're outside of the block slightly off-normal
            let world_p2 = (point.local_p2 * INTERIOR_COLLISION_SCALE_FACTOR + other_center)
                + center_of_mass_2;

            let displaced_p2 = world_p2 + normal2 * INTERIOR_COLLISION_CHECK_DISTANCE;

            let inside2 = is_inside_voxel_collider(chunk_access_2, &physics_state, block_b, displaced_p2);
            if inside2 {
                // ★ 2026-09-03 打点：块2 内判定删除（节流 1s）
                crate::sabledbg!("interior-rm2", 1000,
                    "[sabledbg] interior-rm2 block_b=({},{},{}) wp2=({:.3},{:.3},{:.3})",
                    block_b.x, block_b.y, block_b.z, world_p2.x, world_p2.y, world_p2.z
                );
                return false;
            }
        }

        true
    });

    false
}

fn is_inside_voxel_collider(
    chunk_access: &dyn ChunkAccess,
    physics_state: &PhysicsState,
    ignore_block: IVec3,
    world_pos: DVec3,
) -> bool {
    let block_pos = get_block_pos(world_pos);

    if ignore_block == block_pos {
        return false;
    }

    let Some(chunk) = chunk_access.get_chunk(
        (block_pos.x >> 4) as i32,
        (block_pos.y >> 4) as i32,
        (block_pos.z >> 4) as i32,
    )
    else {
        return false;
    };
    let (block_id, _) =
        chunk.get_block((block_pos.x & 15) as i32, (block_pos.y & 15) as i32, (block_pos.z & 15) as i32);

    if block_id == 0 {
        return false;
    }

    let voxel_data = physics_state
        .voxel_collider_map
        .get((block_id - 1) as usize, block_pos);

    let Some(voxel_data) = voxel_data else {
        return false;
    };

    if voxel_data.is_fluid {
        return false;
    }

    let local_pos = world_pos - crate::prec::to_vec3(block_pos.as_dvec3());

    for &(min_x, min_y, min_z, max_x, max_y, max_z) in &voxel_data.collision_boxes {
        if local_pos.x as Real >= min_x as Real
            && local_pos.x as Real <= max_x as Real
            && local_pos.y as Real >= min_y as Real
            && local_pos.y as Real <= max_y as Real
            && local_pos.z as Real >= min_z as Real
            && local_pos.z as Real <= max_z as Real
        {
            return true;
        }
    }

    false
}
