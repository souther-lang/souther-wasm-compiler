//! What a closure was written among.
//!
//! Not a Souther value: it has no tag and no descriptor, and nothing a program writes can hold
//! one. It is the runtime's own record of which cells a block body reads from around it, kept
//! apart from `value.rs` so that a list, whose descriptor says what its elements are, is never
//! what carries cells of every kind. The compiler makes, fills and reads one through the three
//! exports here and knows nothing of how it is laid out.

use crate::alloc;

/// A capture environment holding that many cells, none put in yet.
#[no_mangle]
pub unsafe extern "C" fn __souther_captures(count: u32) -> u32 {
    let captures = alloc(4 + 4 * count);
    core::ptr::write_unaligned(captures as usize as *mut u32, count);
    captures
}

/// Puts a cell at a position of a capture environment.
#[no_mangle]
pub unsafe extern "C" fn __souther_capture_set(captures: u32, index: u32, value: u32) {
    core::ptr::write_unaligned((captures as usize + 4 + 4 * index as usize) as *mut u32, value);
}

/// The cell at a position of a capture environment.
#[no_mangle]
pub unsafe extern "C" fn __souther_capture_get(captures: u32, index: u32) -> u32 {
    core::ptr::read_unaligned((captures as usize + 4 + 4 * index as usize) as *const u32)
}
