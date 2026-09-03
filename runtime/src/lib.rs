//! The Souther wasm runtime's ABI.
//!
//! What a linked module answers with is decided here and nowhere else. The Java linker copies
//! this crate's compiled module verbatim, appends its own definitions after it, and never reads a
//! code body — so every address, name and protocol a caller depends on is written in this file.
//!
//! # The arena
//!
//! One bump region above the static data. `arena_base` is not this crate's own static end: the
//! linker appends data segments after it, so the base is handed in by `__souther_runtime_init`
//! from a generated start thunk once every segment is placed.
//!
//! Reclaiming is the host's, on the rontolisp recipe: take a mark, allocate the input, call the
//! export, read the returned bytes out of memory, and only then reset. An export that returns a
//! string returns a live pointer into the arena, so a reset before the read frees what the caller
//! is about to look at.
//!
//! # Failure
//!
//! An abort writes a fixed-width record outside the arena and traps. The record holds no pointer:
//! a host that resets the arena before it builds a diagnostic would otherwise read freed bytes
//! for a reason. The generation tells a Souther abort from an ordinary wasm fault — a caller
//! snapshots it before the call and reads the record only when it differs afterwards.

#![no_std]

mod decimal;
mod descriptor;
mod issues;
mod kernel;
mod json;
mod order;
mod temporal;
mod text;
mod value;

use core::panic::PanicInfo;

/// A wasm page, in bytes.
const PAGE: usize = 65536;

/// What this module's callers are compiled against. A linker that reads a different number is
/// looking at a runtime it was not built for.
const ABI_VERSION: u32 = 1;

/// The address the failure record lives at, filled in by `__souther_runtime_init` — it sits
/// between the appended static data and the arena, so it is not known until link time.
static mut FAILURE: usize = 0;

/// The first byte the arena may hand out.
static mut ARENA_BASE: usize = 0;

/// The first byte the arena has not handed out.
static mut ARENA_TOP: usize = 0;

/// The failure record's width in bytes: generation, reason, descriptor, aux0, aux1.
const FAILURE_BYTES: usize = 4 + 4 + 4 + 8 + 8;

/// What the failure record and the arena's own base start at. Only those: an allocation is handed
/// out exactly as long as it was asked for, so that a run of text written piece by piece is one
/// run rather than pieces with gaps between them. A wasm load does not require its address to be
/// aligned, and every payload here is read and written as unaligned anyway.
const ALIGN: usize = 8;

/// Places the arena above everything the link put in static memory.
///
/// The generated start thunk calls this with the end of the last data segment the linker wrote.
/// The failure record is taken from there, and the arena begins after it. Runs before any export
/// does, because a start function runs at instantiation.
#[no_mangle]
pub unsafe extern "C" fn __souther_runtime_init(static_end: u32) {
    let failure = align_up(static_end as usize);
    FAILURE = failure;
    let base = align_up(failure + FAILURE_BYTES);
    ARENA_BASE = base;
    ARENA_TOP = base;
    core::ptr::write_bytes(failure as *mut u8, 0, FAILURE_BYTES);
}

/// Hands out `size` zeroed bytes, growing memory when the arena has run past what is mapped.
///
/// Named for rontolisp's host contract: a host that already stages `(ptr, len)` strings for a
/// rontolisp module stages them for this one the same way.
#[no_mangle]
pub unsafe extern "C" fn __ronto_alloc(size: u32) -> u32 {
    let start = ARENA_TOP;
    let end = start + size as usize;
    if end > memory_bytes() {
        let wanted = (end - memory_bytes()).div_ceil(PAGE);
        if core::arch::wasm32::memory_grow(0, wanted) == usize::MAX {
            __souther_abort(REASON_OUT_OF_MEMORY, 0, size as u64, 0);
        }
    }
    ARENA_TOP = end;
    core::ptr::write_bytes(start as *mut u8, 0, size as usize);
    start as u32
}

/// The first byte the arena has not handed out, for whoever is writing a run of text and wants to
/// know where it began or how far it has got.
pub(crate) unsafe fn next_free() -> u32 {
    ARENA_TOP as u32
}

/// The arena's current top, for a caller that means to pop back to it.
#[no_mangle]
pub unsafe extern "C" fn __ronto_alloc_mark() -> u32 {
    ARENA_TOP as u32
}

/// Pops the arena back to a mark. A mark below the base, or above the current top, is a caller
/// error rather than a value to act on.
#[no_mangle]
pub unsafe extern "C" fn __ronto_alloc_reset(mark: u32) {
    let mark = mark as usize;
    if mark < ARENA_BASE || mark > ARENA_TOP {
        __souther_abort(REASON_BAD_MARK, 0, mark as u64, ARENA_TOP as u64);
    }
    ARENA_TOP = mark;
}

/// Where the failure record is. A host reads it after a trap; a linker needs it to lay out
/// nothing, so this is the only way to find it.
#[no_mangle]
pub unsafe extern "C" fn __souther_failure_addr() -> u32 {
    FAILURE as u32
}

/// The generation the failure record carries now. A caller snapshots this before a call and
/// compares afterwards: a trap with an unchanged generation came from wasm, not from Souther.
#[no_mangle]
pub unsafe extern "C" fn __souther_failure_generation() -> u32 {
    core::ptr::read((FAILURE + OFF_GENERATION) as *const u32)
}

/// What this module answers as. Read before anything else is trusted about it.
#[no_mangle]
pub extern "C" fn __souther_abi_version() -> u32 {
    ABI_VERSION
}

/// Ends the call with a reason the host can name, without handing it a pointer to read it from.
///
/// The record is written before the trap and holds only fixed-width values, so a host that resets
/// the arena on its way to building a diagnostic still has the reason. `descriptor` names the type
/// the reason is about, in the generated descriptor table; the two `aux` words carry whatever the
/// reason itself needs, bounded.
#[no_mangle]
pub unsafe extern "C" fn __souther_abort(reason: u32, descriptor: u32, aux0: u64, aux1: u64) -> ! {
    let generation = core::ptr::read((FAILURE + OFF_GENERATION) as *const u32);
    core::ptr::write((FAILURE + OFF_GENERATION) as *mut u32, generation.wrapping_add(1));
    core::ptr::write((FAILURE + OFF_REASON) as *mut u32, reason);
    core::ptr::write((FAILURE + OFF_DESCRIPTOR) as *mut u32, descriptor);
    core::ptr::write_unaligned((FAILURE + OFF_AUX0) as *mut u64, aux0);
    core::ptr::write_unaligned((FAILURE + OFF_AUX1) as *mut u64, aux1);
    core::arch::wasm32::unreachable()
}

#[link(wasm_import_module = "souther")]
extern "C" {
    /// The one crossing out of the module.
    ///
    /// A host owns no part of this memory's allocation, so the buffer a behavior's answer is
    /// written into is the caller's: `out_ptr` and `out_cap` come from the arena. The result is
    /// the length the answer wants, which a caller compares with the capacity it offered — a
    /// larger number means nothing was written and the call is made again against a buffer that
    /// fits.
    fn host_call(behavior_id: u32, in_ptr: u32, in_len: u32, out_ptr: u32, out_cap: u32) -> u32;
}

/// Reaches an injected behavior, allocating the answer's buffer here rather than letting the host
/// move this module's bump pointer.
///
/// Answers the arena pointer the encoded answer sits at, and its length, packed as the low and
/// high halves of an `i64` — one result rather than a pair, so a call site keeps no scratch.
#[no_mangle]
pub unsafe extern "C" fn __souther_host_call(behavior_id: u32, in_ptr: u32, in_len: u32) -> u64 {
    let mut capacity = 256u32;
    loop {
        let out = __ronto_alloc(capacity);
        let wanted = host_call(behavior_id, in_ptr, in_len, out, capacity);
        if wanted <= capacity {
            return (out as u64) | ((wanted as u64) << 32);
        }
        capacity = wanted;
    }
}

/// Applies a block to one value, from inside this crate.
///
/// A block is a function taking what it was written among ahead of what it is applied to, so what
/// goes through the table is the pair.
#[no_mangle]
pub unsafe extern "C" fn __souther_call_block(closure: u32, argument: u32) -> u32 {
    let target: extern "C" fn(u32, u32) -> u32 =
        core::mem::transmute(value::__souther_closure_slot(closure) as usize);
    target(value::__souther_closure_captured(closure), argument)
}

/// Reaches a generated function through the module's function table.
///
/// The table is the one thing a generated definition and this crate both write into — a closure's
/// cell holds the slot its body sits in, and a descriptor holds the slot that checks a type's
/// invariant — so it is declared here and exported, rather than left to whatever table a
/// recompilation of this crate happened to produce. On wasm32 a function pointer is its table
/// slot, which is what makes the transmute the call it looks like.
///
/// The argument and the answer are both an arena pointer: everything a generated body passes is.
#[no_mangle]
pub unsafe extern "C" fn __souther_call_slot(slot: u32, argument: u32) -> u32 {
    let target: extern "C" fn(u32) -> u32 = core::mem::transmute(slot as usize);
    target(argument)
}

/// Offsets into the failure record. A host reads it as five fields at these, little-endian.
const OFF_GENERATION: usize = 0;
const OFF_REASON: usize = 4;
const OFF_DESCRIPTOR: usize = 8;
const OFF_AUX0: usize = 12;
const OFF_AUX1: usize = 20;

/// The arena asked for more than the engine would map. `aux0` is the size that did not fit.
pub const REASON_OUT_OF_MEMORY: u32 = 1;
/// A reset was handed a mark the arena never issued. `aux0` is the mark, `aux1` the top.
pub const REASON_BAD_MARK: u32 = 2;
/// What was handed in is not one JSON document. `aux0` is where the reading stopped.
pub const REASON_MALFORMED_JSON: u32 = 3;
/// A whole number's arithmetic left the range an `Int` holds. `aux0` and `aux1` are the operands.
pub const REASON_INT_OVERFLOW: u32 = 5;
/// A division by zero, which the `/` operator treats as a model bug rather than a case.
pub const REASON_DIVISION_BY_ZERO: u32 = 6;
/// A value was made inside a behavior that its type says nothing may be. `aux0` is which of the
/// type's invariants it breaks.
pub const REASON_INVARIANT_VIOLATION: u32 = 8;
/// A position the program said gets no value. `aux0` is where the reason it was written with is
/// and `aux1` how long it is — in static memory, so it is there after the arena has been reset.
/// Raised by generated code and named here so that the numbers are all in one list.
pub const REASON_NOTHING_TO_ANSWER_WITH: u32 = 9;
/// An index or a count outside what the operation admits. `aux0` is what was asked for.
pub const REASON_OUT_OF_RANGE: u32 = 10;
/// A match ran out of arms. The checker settles that one always answers, so reaching this means
/// the emitter tested for the wrong thing rather than that the model left a case out.
pub const REASON_NO_ARM: u32 = 7;

/// A value whose tag nothing here knows. What a decoder was handed never reaches this: a value is
/// made by generated code, so a tag no one knows means the emitter is wrong rather than the input.
pub const REASON_NOT_A_VALUE: u32 = 4;

/// The arena, for this crate's own modules. The exported name is the host's; this is the one a
/// caller inside the module writes, so that what a host contract is called and what the code says
/// stay one thing rather than two spellings of it.
pub(crate) unsafe fn alloc(size: u32) -> u32 {
    __ronto_alloc(size)
}

/// Ending the call, for this crate's own modules.
pub(crate) unsafe fn abort(reason: u32, descriptor: u32, aux0: u64, aux1: u64) -> ! {
    __souther_abort(reason, descriptor, aux0, aux1)
}

fn align_up(n: usize) -> usize {
    (n + ALIGN - 1) & !(ALIGN - 1)
}

fn memory_bytes() -> usize {
    core::arch::wasm32::memory_size(0) * PAGE
}

/// `panic = "abort"` leaves this unreachable in a release build; it is here because a `no_std`
/// crate does not link without it.
#[panic_handler]
fn panic(_: &PanicInfo) -> ! {
    core::arch::wasm32::unreachable()
}
