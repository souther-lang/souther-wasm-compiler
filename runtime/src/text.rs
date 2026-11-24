//! The run of bytes an answer is written into.
//!
//! Written into a buffer that grows rather than onto the arena's top. A run built by taking a mark
//! and allocating pieces holds together only while nothing else allocates between the first piece
//! and the last, and what a piece is made of is free to allocate — an amount works out the form it
//! is written in before it can be written. So the pieces are copied into somewhere that says where
//! it ends, and what else the arena hands out in between goes wherever it likes.

use crate::alloc;

/// Bytes written one piece after another into room that grows to hold them: where they are, how
/// many are written, and how much room there is.
///
/// The one account of growing a run. Two buffers grow so — the run an answer is written into and
/// the one an issue's metadata is — and each kept its own copy of how, which worked out how much
/// was written in thirty-two bits that a size past what an address can say wrapped round in: the
/// room was then taken to be enough, and the piece copied past it. How much is written is worked
/// out once, checked, and is what both the growing and the count go by.
pub(crate) struct Run {
    at: u32,
    written: u32,
    room: u32,
}

impl Run {
    /// A run with no room, which the first piece pushed to it takes room for.
    pub(crate) const EMPTY: Run = Run { at: 0, written: 0, room: 0 };

    /// Starts the run in room of its own, forgetting what it held.
    pub(crate) unsafe fn begin(&mut self, room: u32) {
        self.at = alloc(room);
        self.written = 0;
        self.room = room;
    }

    /// Adds bytes to the run, moving it into twice the room it then needs where it has not got it.
    pub(crate) unsafe fn push(&mut self, from: u32, length: u32) {
        let written = crate::together(self.written, length);
        if written > self.room {
            let wanted = crate::room(0, written, 2);
            let wider = alloc(wanted);
            if self.written > 0 {
                core::ptr::copy_nonoverlapping(
                    self.at as *const u8, wider as *mut u8, self.written as usize);
            }
            self.at = wider;
            self.room = wanted;
        }
        core::ptr::copy_nonoverlapping(
            from as *const u8, (self.at + self.written) as *mut u8, length as usize);
        self.written = written;
    }

    /// Where the run is and how long it is.
    pub(crate) fn ended(&self) -> (u32, u32) {
        (self.at, self.written)
    }

    /// Takes the run back to where it was `written` bytes long, forgetting what was written after.
    pub(crate) fn back_to(&mut self, written: u32) {
        self.written = written;
    }
}

/// The run being written.
static mut RUN: Run = Run::EMPTY;

/// How much room a run starts with. Enough that an ordinary answer never moves.
const INITIAL_ROOM: u32 = 256;

/// The run being written, which only this crate's one thread writes.
unsafe fn run() -> &'static mut Run {
    &mut *core::ptr::addr_of_mut!(RUN)
}

/// Starts a run, forgetting whatever the last one was.
pub unsafe fn begin() {
    run().begin(INITIAL_ROOM);
}

/// Forgets the run, as the arena it lives in is popped. A run pushed to after this starts in room of
/// its own rather than writing where the last one was.
pub unsafe fn forget() {
    *run() = Run::EMPTY;
}

/// Adds bytes to the run.
pub unsafe fn push(from: u32, length: u32) {
    run().push(from, length);
}

/// Adds bytes this crate wrote down itself.
pub unsafe fn put(bytes: &[u8]) {
    push(bytes.as_ptr() as u32, bytes.len() as u32);
}

/// Where the run is and how long it is.
pub unsafe fn ended() -> (u32, u32) {
    run().ended()
}

/// How much of the run is written: a place in it that stays put however the run grows, which its
/// address does not.
pub unsafe fn so_far() -> u32 {
    run().ended().1
}

/// Takes the run back to where it was `so_far` once, forgetting what was written after.
pub unsafe fn back_to(written: u32) {
    run().back_to(written);
}
