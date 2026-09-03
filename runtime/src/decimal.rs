//! A `Decimal`: an amount, and how it was written.
//!
//! Digits without a bound and a scale that counts how many of them are after the point. Two values
//! that differ only in scale are one amount and two ways of writing it, so both are kept — which
//! one crosses is settled by the canonical form, and which one is equal to which is settled by the
//! amount.
//!
//! ```text
//! +0   u32 tag
//! +4   i32 scale
//! +8   i32 sign, which is minus one, zero or one
//! +12  u32 how many limbs
//! +16  limbs, least significant first
//! ```
//!
//! A limb holds a billion, so the digits go in and out ten at a time and nothing has to divide by
//! a power of two to write a number down.

use crate::value::TAG_DECIMAL;
use crate::{abort, alloc, REASON_OUT_OF_RANGE};

/// How many digits a limb holds. A limb is that many digits of the number and nothing else, so
/// putting digits in and taking them out is a division by ten and never by a power of two.
const LIMB_DIGITS: u32 = 9;

const OFF_SCALE: usize = 4;
const OFF_SIGN: usize = 8;
const OFF_LIMBS: usize = 12;
const HEADER: usize = 16;

/// How many digits an amount may be spelt out into before it is left in the form it came in.
///
/// Souther's own number, and the reason it exists is that a small input must not be able to ask for
/// an arbitrarily large output — eleven characters can name a million digits.
const MAX_SPELT_OUT_DIGITS: i64 = 1000;

/// A `Decimal` cell with room for that many limbs, holding nothing yet.
pub unsafe fn made(scale: i32, sign: i32, limbs: u32) -> u32 {
    let cell = alloc(HEADER as u32 + 4 * limbs);
    put(cell, 0, TAG_DECIMAL);
    put(cell, OFF_SCALE, scale as u32);
    put(cell, OFF_SIGN, sign as u32);
    put(cell, OFF_LIMBS, limbs);
    cell
}

/// How many places of a `Decimal` are after the point.
pub unsafe fn scale(cell: u32) -> i32 {
    get(cell, OFF_SCALE) as i32
}

/// Minus one, zero or one.
pub unsafe fn sign(cell: u32) -> i32 {
    get(cell, OFF_SIGN) as i32
}

/// How many limbs the digits take.
pub unsafe fn limbs(cell: u32) -> u32 {
    get(cell, OFF_LIMBS)
}

/// One limb of the digits, least significant first.
pub unsafe fn limb(cell: u32, index: u32) -> u32 {
    get(cell, HEADER + 4 * index as usize)
}

unsafe fn set_limb(cell: u32, index: u32, value: u32) {
    put(cell, HEADER + 4 * index as usize, value);
}

unsafe fn get(cell: u32, offset: usize) -> u32 {
    core::ptr::read_unaligned((cell as usize + offset) as *const u32)
}

unsafe fn put(cell: u32, offset: usize, value: u32) {
    core::ptr::write_unaligned((cell as usize + offset) as *mut u32, value);
}

/// The digits of a `Decimal` written out, most significant first, into a buffer this hands back.
///
/// The count is what a reader walks; the pointer is the arena's, so it lives as long as the call.
pub unsafe fn digits(cell: u32) -> (u32, u32) {
    let held = limbs(cell);
    if held == 0 || sign(cell) == 0 {
        let at = alloc(1);
        core::ptr::write(at as *mut u8, b'0');
        return (at, 1);
    }
    let top = limb(cell, held - 1);
    let mut leading = 0;
    let mut rest = top;
    while rest > 0 {
        leading += 1;
        rest /= 10;
    }
    let total = leading + (held - 1) * LIMB_DIGITS;
    let at = alloc(total);
    let mut written = 0;
    write_number(at, &mut written, top, leading);
    for i in (0..held - 1).rev() {
        write_number(at, &mut written, limb(cell, i), LIMB_DIGITS);
    }
    (at, total)
}

unsafe fn write_number(at: u32, written: &mut u32, value: u32, places: u32) {
    let mut rest = value;
    for i in (0..places).rev() {
        core::ptr::write((at + *written + i) as *mut u8, b'0' + (rest % 10) as u8);
        rest /= 10;
    }
    *written += places;
}

/// A `Decimal` from digits already written out, most significant first.
pub unsafe fn of_digits(at: u32, length: u32, scale: i32, negative: bool) -> u32 {
    let mut first = 0;
    while first + 1 < length && core::ptr::read((at + first) as *const u8) == b'0' {
        first += 1;
    }
    let held = length - first;
    if held == 1 && core::ptr::read((at + first) as *const u8) == b'0' {
        return made(scale, 0, 0);
    }
    let count = held.div_ceil(LIMB_DIGITS);
    let cell = made(scale, if negative { -1 } else { 1 }, count);
    let mut end = length;
    for i in 0..count {
        let start = if end >= first + LIMB_DIGITS { end - LIMB_DIGITS } else { first };
        let mut value = 0u32;
        for j in start..end {
            value = value * 10 + (core::ptr::read((at + j) as *const u8) - b'0') as u32;
        }
        set_limb(cell, i, value);
        end = start;
    }
    cell
}

/// How many digits an amount is written with, which is what a precision is.
pub unsafe fn precision(cell: u32) -> u32 {
    let (_, length) = digits(cell);
    length
}

/// The same amount with its trailing zeros gone, as far as the scale can go down.
///
/// Every way of writing nothing is one amount, so nothing comes back as nothing at scale zero.
pub unsafe fn stripped(cell: u32) -> u32 {
    if sign(cell) == 0 {
        return made(0, 0, 0);
    }
    let (at, length) = digits(cell);
    let mut end = length;
    let mut held = scale(cell);
    while end > 1
        && core::ptr::read((at + end - 1) as *const u8) == b'0'
        && held > i32::MIN
    {
        end -= 1;
        held -= 1;
    }
    of_digits(at, end, held, sign(cell) < 0)
}

/// The one form of an amount that crosses.
///
/// The trailing zeros go, and then an amount whose scale went below zero is spelt out again where
/// spelling it out is bounded — so what a caller reads is one form per amount rather than whichever
/// the program happened to be holding.
pub unsafe fn canonical(cell: u32) -> u32 {
    let held = stripped(cell);
    if scale(held) >= 0 {
        return held;
    }
    let spelled = precision(held) as i64 - scale(held) as i64;
    if spelled > MAX_SPELT_OUT_DIGITS {
        return held;
    }
    let (at, length) = digits(held);
    let zeros = (-scale(held)) as u32;
    let out = alloc(length + zeros);
    core::ptr::copy_nonoverlapping(at as *const u8, out as *mut u8, length as usize);
    core::ptr::write_bytes((out + length) as *mut u8, b'0', zeros as usize);
    of_digits(out, length + zeros, 0, sign(held) < 0)
}

/// A `Decimal` as it is written: what its own account of itself says, in the notation that account
/// uses — plain where the point falls among or just before the digits, and an exponent where it
/// falls a long way before them.
///
/// Answers where the text went and how long it is.
pub unsafe fn written(cell: u32) -> (u32, u32) {
    let (at, length) = digits(cell);
    let held = scale(cell);
    if held == 0 {
        return signed(at, length, sign(cell) < 0);
    }
    let adjusted = length as i64 - 1 - held as i64;
    if held > 0 && adjusted >= -6 {
        return plain(at, length, held as u32, adjusted, sign(cell) < 0);
    }
    scientific(at, length, adjusted, sign(cell) < 0)
}

/// The digits with a minus in front where there is one.
unsafe fn signed(at: u32, length: u32, negative: bool) -> (u32, u32) {
    if !negative {
        return (at, length);
    }
    let out = alloc(length + 1);
    core::ptr::write(out as *mut u8, b'-');
    core::ptr::copy_nonoverlapping(at as *const u8, (out + 1) as *mut u8, length as usize);
    (out, length + 1)
}

/// The digits with a point among them, or with zeros before them where the point falls first.
unsafe fn plain(at: u32, length: u32, held: u32, adjusted: i64, negative: bool) -> (u32, u32) {
    let out = crate::next_free();
    let mut total = 0;
    if negative {
        total += byte(b'-');
    }
    if adjusted >= 0 {
        let before = length - held;
        total += copy(at, before);
        total += byte(b'.');
        total += copy(at + before, held);
    } else {
        total += byte(b'0');
        total += byte(b'.');
        for _ in 0..(-adjusted - 1) {
            total += byte(b'0');
        }
        total += copy(at, length);
    }
    (out, total)
}

/// One digit, a point, the rest, and the power of ten the point moved by.
unsafe fn scientific(at: u32, length: u32, adjusted: i64, negative: bool) -> (u32, u32) {
    let out = crate::next_free();
    let mut total = 0;
    if negative {
        total += byte(b'-');
    }
    total += copy(at, 1);
    if length > 1 {
        total += byte(b'.');
        total += copy(at + 1, length - 1);
    }
    total += byte(b'E');
    if adjusted >= 0 {
        total += byte(b'+');
    } else {
        total += byte(b'-');
    }
    let magnitude = if adjusted < 0 { -adjusted } else { adjusted } as u64;
    let mut room = [0u8; 20];
    let mut places = 0;
    let mut rest = magnitude;
    if rest == 0 {
        room[0] = b'0';
        places = 1;
    }
    while rest > 0 {
        room[places] = b'0' + (rest % 10) as u8;
        places += 1;
        rest /= 10;
    }
    for i in (0..places).rev() {
        total += byte(room[i]);
    }
    (out, total)
}

unsafe fn byte(held: u8) -> u32 {
    let at = alloc(1);
    core::ptr::write(at as *mut u8, held);
    1
}

unsafe fn copy(from: u32, length: u32) -> u32 {
    let at = alloc(length);
    core::ptr::copy_nonoverlapping(from as *const u8, at as *mut u8, length as usize);
    length
}

/// Reads a number as it was written into the amount it names.
///
/// Answers zero where the text is not a number, which is what a reader of a document has to be
/// told rather than ended for.
pub unsafe fn parse(at: u32, length: u32) -> u32 {
    let mut i = 0;
    let negative = length > 0 && core::ptr::read(at as *const u8) == b'-';
    if negative || (length > 0 && core::ptr::read(at as *const u8) == b'+') {
        i = 1;
    }
    let out = crate::next_free();
    let mut written = 0;
    let mut held: i64 = 0;
    let mut seen = false;
    while i < length {
        let byte = core::ptr::read((at + i) as *const u8);
        if byte.is_ascii_digit() {
            let piece = alloc(1);
            core::ptr::write(piece as *mut u8, byte);
            written += 1;
            seen = true;
            i += 1;
        } else {
            break;
        }
    }
    if i < length && core::ptr::read((at + i) as *const u8) == b'.' {
        i += 1;
        while i < length {
            let byte = core::ptr::read((at + i) as *const u8);
            if !byte.is_ascii_digit() {
                break;
            }
            let piece = alloc(1);
            core::ptr::write(piece as *mut u8, byte);
            written += 1;
            held += 1;
            seen = true;
            i += 1;
        }
    }
    if !seen {
        return 0;
    }
    if i < length && (core::ptr::read((at + i) as *const u8) | 0x20) == b'e' {
        i += 1;
        let mut down = false;
        if i < length {
            let byte = core::ptr::read((at + i) as *const u8);
            if byte == b'-' || byte == b'+' {
                down = byte == b'-';
                i += 1;
            }
        }
        let mut power: i64 = 0;
        let start = i;
        while i < length {
            let byte = core::ptr::read((at + i) as *const u8);
            if !byte.is_ascii_digit() {
                break;
            }
            power = power * 10 + (byte - b'0') as i64;
            if power > i32::MAX as i64 {
                abort(REASON_OUT_OF_RANGE, 0, power as u64, 0);
            }
            i += 1;
        }
        if i == start {
            return 0;
        }
        held += if down { power } else { -power };
    }
    if i != length {
        return 0;
    }
    if held > i32::MAX as i64 || held < i32::MIN as i64 {
        abort(REASON_OUT_OF_RANGE, 0, held as u64, 0);
    }
    of_digits(out, written, held as i32, negative)
}

/// Where one amount stands relative to another, by what they are worth and not by how written.
pub unsafe fn compare(left: u32, right: u32) -> i32 {
    let a = sign(left);
    let b = sign(right);
    if a != b {
        return if a < b { -1 } else { 1 };
    }
    if a == 0 {
        return 0;
    }
    let by_size = magnitudes(left, right);
    if a < 0 {
        -by_size
    } else {
        by_size
    }
}

/// The two amounts' sizes compared, ignoring which way they lean.
unsafe fn magnitudes(left: u32, right: u32) -> i32 {
    let (a, a_length) = digits(left);
    let (b, b_length) = digits(right);
    // Line the points up: what is after the point in one may not be in the other.
    let a_places = a_length as i64 - scale(left) as i64;
    let b_places = b_length as i64 - scale(right) as i64;
    if a_places != b_places {
        return if a_places < b_places { -1 } else { 1 };
    }
    let longer = if a_length > b_length { a_length } else { b_length };
    for i in 0..longer {
        let x = if i < a_length { core::ptr::read((a + i) as *const u8) } else { b'0' };
        let y = if i < b_length { core::ptr::read((b + i) as *const u8) } else { b'0' };
        if x != y {
            return if x < y { -1 } else { 1 };
        }
    }
    0
}
