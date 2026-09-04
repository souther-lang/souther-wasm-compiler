//! The run of bytes an answer is written into.
//!
//! Written into a buffer that grows rather than onto the arena's top. A run built by taking a mark
//! and allocating pieces holds together only while nothing else allocates between the first piece
//! and the last, and what a piece is made of is free to allocate — an amount works out the form it
//! is written in before it can be written. So the pieces are copied into somewhere that says where
//! it ends, and what else the arena hands out in between goes wherever it likes.

use crate::alloc;

/// Where the run being written is.
static mut AT: u32 = 0;
/// How much of it is written.
static mut WRITTEN: u32 = 0;
/// How much room it has.
static mut ROOM: u32 = 0;

/// How much room a run starts with. Enough that an ordinary answer never moves.
const INITIAL_ROOM: u32 = 256;

/// Starts a run, forgetting whatever the last one was.
pub unsafe fn begin() {
    AT = alloc(INITIAL_ROOM);
    WRITTEN = 0;
    ROOM = INITIAL_ROOM;
}

/// Adds bytes to the run.
pub unsafe fn push(from: u32, length: u32) {
    if WRITTEN + length > ROOM {
        let wanted = (WRITTEN + length) * 2;
        let wider = alloc(wanted);
        core::ptr::copy_nonoverlapping(AT as *const u8, wider as *mut u8, WRITTEN as usize);
        AT = wider;
        ROOM = wanted;
    }
    core::ptr::copy_nonoverlapping(from as *const u8, (AT + WRITTEN) as *mut u8, length as usize);
    WRITTEN += length;
}

/// Adds bytes this crate wrote down itself.
pub unsafe fn put(bytes: &[u8]) {
    push(bytes.as_ptr() as u32, bytes.len() as u32);
}

/// Where the run is and how long it is.
pub unsafe fn ended() -> (u32, u32) {
    (AT, WRITTEN)
}
