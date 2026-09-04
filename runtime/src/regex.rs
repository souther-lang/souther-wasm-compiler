//! Whether the whole of a string is what a pattern describes.
//!
//! The pattern was read where it was written and is here as a list of steps, so nothing is parsed
//! at run time. The walk keeps every step it could be at rather than trying one and coming back:
//! there is no way to refer to what a group matched, so which of several ways the pattern was
//! satisfied never has to be known, and holding them all at once is the whole of it. That also
//! bounds the work — a step is held once per character, so a pattern that would send a walk that
//! tries one way at a time down a great many of them costs no more here than any other.
//!
//! ```text
//! +0  u32 how many steps
//! then, per step: u32 what it does, u32, u32
//! ```
//!
//! A set of characters sits on its own, as the runs it names and nothing else. What a class
//! leaves out was worked out where the pattern was read, so this only ever asks whether a
//! character is in one of the runs:
//!
//! ```text
//! +0  u32 nothing
//! +4  u32 how many runs
//! then, per run: u32 the first, u32 the last
//! ```

use crate::alloc;

const MATCH_ONE: u32 = 0;
const MATCH_ANY: u32 = 1;
const MATCH_CLASS: u32 = 2;
const FORK: u32 = 3;
const GO: u32 = 4;
const DONE: u32 = 5;

/// Where the walk could be, and which round each step was last put there in.
struct Where {
    steps: u32,
    held: u32,
    marks: u32,
    kept: u32,
}

impl Where {
    unsafe fn new(steps: u32) -> Where {
        let held = alloc(steps * 4);
        let marks = alloc(steps);
        for i in 0..steps {
            core::ptr::write((marks + i) as *mut u8, 0);
        }
        Where { steps, held, marks, kept: 0 }
    }

    unsafe fn at(&self, index: u32) -> u32 {
        core::ptr::read_unaligned((self.held + index * 4) as *const u32)
    }
}

/// Whether the whole of the string at `at` is what the machine at `machine` describes.
pub unsafe fn matches(at: u32, length: u32, machine: u32) -> bool {
    let steps = core::ptr::read_unaligned(machine as *const u32);
    let mut current = Where::new(steps);
    let mut next = Where::new(steps);
    let mut round = 1u8;
    add(machine, &mut current, round, 0);

    let mut i = 0;
    loop {
        let mut done = false;
        for k in 0..current.kept {
            if step(machine, current.at(k), 0) == DONE && i >= length {
                done = true;
            }
        }
        if done {
            return true;
        }
        if i >= length || current.kept == 0 {
            return false;
        }
        let (point, width) = code_point(at + i, length - i);
        round = round.wrapping_add(1);
        if round == 0 {
            round = 1;
        }
        next.kept = 0;
        for k in 0..current.kept {
            let pc = current.at(k);
            let taken = match step(machine, pc, 0) {
                MATCH_ONE => point == step(machine, pc, 1),
                MATCH_ANY => !ends_a_line(point),
                MATCH_CLASS => in_class(step(machine, pc, 1), point),
                _ => false,
            };
            if taken {
                add(machine, &mut next, round, pc + 1);
            }
        }
        i += width;
        core::mem::swap(&mut current, &mut next);
    }
}

/// Puts a step among the ones the walk could be at, and everything reachable from it without
/// reading a character. The marks say which round a step was last put in, so a step several ways
/// arrive at is held once and the list stays as short as the machine.
unsafe fn add(machine: u32, into: &mut Where, round: u8, pc: u32) {
    if pc >= into.steps || core::ptr::read((into.marks + pc) as *const u8) == round {
        return;
    }
    core::ptr::write((into.marks + pc) as *mut u8, round);
    match step(machine, pc, 0) {
        FORK => {
            add(machine, into, round, step(machine, pc, 1));
            add(machine, into, round, step(machine, pc, 2));
        }
        GO => add(machine, into, round, step(machine, pc, 1)),
        _ => {
            core::ptr::write_unaligned((into.held + into.kept * 4) as *mut u32, pc);
            into.kept += 1;
        }
    }
}

unsafe fn step(machine: u32, pc: u32, field: u32) -> u32 {
    core::ptr::read_unaligned((machine + 4 + pc * 12 + field * 4) as *const u32)
}

unsafe fn in_class(set: u32, point: u32) -> bool {
    let runs = core::ptr::read_unaligned((set + 4) as *const u32);
    for i in 0..runs {
        let first = core::ptr::read_unaligned((set + 8 + i * 8) as *const u32);
        let last = core::ptr::read_unaligned((set + 12 + i * 8) as *const u32);
        if point >= first && point <= last {
            return true;
        }
    }
    false
}

/// The characters a dot does not stand for, which is what ends a line.
fn ends_a_line(point: u32) -> bool {
    matches!(point, 0x0a | 0x0b | 0x0c | 0x0d | 0x85 | 0x2028 | 0x2029)
}

/// The code point the bytes at `at` start with, and how many bytes it took.
unsafe fn code_point(at: u32, left: u32) -> (u32, u32) {
    let first = core::ptr::read(at as *const u8);
    if first < 0x80 {
        return (first as u32, 1);
    }
    let (width, mut point) = if first < 0xe0 {
        (2u32, (first & 0x1f) as u32)
    } else if first < 0xf0 {
        (3u32, (first & 0x0f) as u32)
    } else {
        (4u32, (first & 0x07) as u32)
    };
    if width > left {
        return (0xfffd, left);
    }
    for i in 1..width {
        point = (point << 6) | (core::ptr::read((at + i) as *const u8) & 0x3f) as u32;
    }
    (point, width)
}
