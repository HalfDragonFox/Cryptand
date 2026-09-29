//! 精度适配层（2026-09-05 用户方案：不能通用的代码单独文件 + feature 选择编译）。
//!
//! marten（数据/体素层）固定 f64（`marten::Real = f64`）；rapier（物理求解层）精度
//! 由 feature 决定（prec-f32=rapier3d f32 / prec-f64=rapier3d-f64）。
//! - `Real`：统一精度类型（f64 模式=marten::Real(=f64)=rapier-f64 Real；f32 模式=
//!   rapier3d f32 Real）。代码对 rapier 类型（RigidBodyVelocity<Real> 等）直接用 `Real`。
//! - `to_m`/`to_r`：marten(固定f64)边界转换——写 marten 字段（collision_boxes/friction/
//!   volume/restitution 等 f64）用 `to_m`；读 marten f64 值转 rapier `Real` 用 `to_r`。
//! - f64 模式二者为恒等（零开销）；f32 模式为 as 转换（精度截断，符合 f32 物理语义）。

#[cfg(feature = "prec-f64")]
pub use marten::Real;
#[cfg(not(feature = "prec-f64"))]
pub use rapier3d::prelude::Real;

// ★ 2026-09-05 typedef 式精度切换（同 C typedef 思路）：DVec3 按 feature 指向不同类型。
//   - f64 模式：DVec3 = glam DVec3（恒 f64）——与 rapier3d-f64 一致（代码原样）。
//   - f32 模式：DVec3 = 本层 Vector（f32）——代码里 DVec3 与 Vec3/Real 混算全部变成
//     f32 运算（类型一致，硬编码 f64 的混用点自然消失；物理求解本来就是 f32）。
/// 全代码统一使用本别名（lib.rs 顶部 `pub type` 可再 re-export）。
#[cfg(feature = "prec-f64")]
pub type DVec3 = rapier3d::glamx::DVec3;
#[cfg(not(feature = "prec-f64"))]
pub type DVec3 = rapier3d::math::Vector;

/// marten → 本层类型（marten 固定 f64；f64 模式恒等，f32 模式截断）。
#[cfg(feature = "prec-f64")]
#[inline]
pub fn to_r(x: f64) -> Real {
    x
}
#[cfg(not(feature = "prec-f64"))]
#[inline]
pub fn to_r(x: f64) -> Real {
    x as Real
}

/// 本层类型 → marten（marten 固定 f64；f64 模式恒等，f32 模式升格）。
#[cfg(feature = "prec-f64")]
#[inline]
pub fn to_m(x: Real) -> f64 {
    x
}
#[cfg(not(feature = "prec-f64"))]
#[inline]
pub fn to_m(x: Real) -> f64 {
    x as f64
}

/// IVec3 构造适配：rapier3d-f64 的 math::IVector = i64 版（glam I64Vec3 系），
/// rapier3d(f32) 的 math::IVector = i32 版（glam IVec3）。统一 i64 入参，按模式转换。
#[cfg(feature = "prec-f64")]
#[inline]
pub fn ivec3(x: i64, y: i64, z: i64) -> rapier3d::math::IVector {
    rapier3d::math::IVector::new(x, y, z)
}
#[cfg(not(feature = "prec-f64"))]
#[inline]
pub fn ivec3(x: i64, y: i64, z: i64) -> rapier3d::math::IVector {
    rapier3d::math::IVector::new(x as i32, y as i32, z as i32)
}

/// DVec3（恒 f64，glamx）→ 本层 Vec3（f64 模式=DVec3 恒等；f32 模式=f32 Vec3 截断）。
#[cfg(feature = "prec-f64")]
#[inline]
pub fn to_vec3(d: rapier3d::glamx::DVec3) -> rapier3d::math::Vector {
    d
}
#[cfg(not(feature = "prec-f64"))]
#[inline]
pub fn to_vec3(d: rapier3d::glamx::DVec3) -> rapier3d::math::Vector {
    d.as_vec3()
}

/// 本层 Vec3（随精度）→ IVec3（f64 模式 i64 版；f32 模式 i32 版）。
/// floor 后转换（坐标格对齐，与 as_i64vec3/as_ivec3 语义一致）。
#[cfg(feature = "prec-f64")]
#[inline]
pub fn floor_to_ivec(v: rapier3d::math::Vector) -> rapier3d::math::IVector {
    v.floor().as_i64vec3()
}
#[cfg(not(feature = "prec-f64"))]
#[inline]
pub fn floor_to_ivec(v: rapier3d::math::Vector) -> rapier3d::math::IVector {
    v.floor().as_ivec3()
}
