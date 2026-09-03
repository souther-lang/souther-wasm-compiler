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

/// The digits of an amount, lined up so that both have the same number after the point.
///
/// Answers where each run of digits is, how long each is, and the scale they now share.
unsafe fn aligned(left: u32, right: u32) -> (u32, u32, u32, u32, i32) {
    let (a, a_length) = digits(left);
    let (b, b_length) = digits(right);
    let a_scale = scale(left);
    let b_scale = scale(right);
    if a_scale == b_scale {
        return (a, a_length, b, b_length, a_scale);
    }
    if a_scale < b_scale {
        let by = (b_scale as i64 - a_scale as i64) as u32;
        (padded(a, a_length, by), a_length + by, b, b_length, b_scale)
    } else {
        let by = (a_scale as i64 - b_scale as i64) as u32;
        (a, a_length, padded(b, b_length, by), b_length + by, a_scale)
    }
}

/// The digits with that many zeros after them, which is the same amount at a larger scale.
unsafe fn padded(at: u32, length: u32, by: u32) -> u32 {
    let out = alloc(length + by);
    core::ptr::copy_nonoverlapping(at as *const u8, out as *mut u8, length as usize);
    core::ptr::write_bytes((out + length) as *mut u8, b'0', by as usize);
    out
}

/// Which of two runs of digits is the larger number.
///
/// The zeros in front are not part of the number: a subtraction leaves as many digits as it was
/// given and a product leaves one more than it needs, so a run reaching here says nothing about
/// how large it is by how long it is.
unsafe fn larger(a: u32, a_length: u32, b: u32, b_length: u32) -> i32 {
    let (a, a_length) = significant(a, a_length);
    let (b, b_length) = significant(b, b_length);
    if a_length != b_length {
        return if a_length < b_length { -1 } else { 1 };
    }
    for i in 0..a_length {
        let x = core::ptr::read((a + i) as *const u8);
        let y = core::ptr::read((b + i) as *const u8);
        if x != y {
            return if x < y { -1 } else { 1 };
        }
    }
    0
}

/// A run of digits without the zeros in front of it.
unsafe fn significant(at: u32, length: u32) -> (u32, u32) {
    let mut first = 0;
    while first + 1 < length && core::ptr::read((at + first) as *const u8) == b'0' {
        first += 1;
    }
    (at + first, length - first)
}

/// Two runs of digits added, most significant first.
unsafe fn sum(a: u32, a_length: u32, b: u32, b_length: u32) -> (u32, u32) {
    let longer = if a_length > b_length { a_length } else { b_length };
    let out = alloc(longer + 1);
    let mut carry = 0u8;
    for i in 0..longer {
        let x = digit_from_end(a, a_length, i);
        let y = digit_from_end(b, b_length, i);
        let held = x + y + carry;
        core::ptr::write((out + longer - i) as *mut u8, b'0' + held % 10);
        carry = held / 10;
    }
    core::ptr::write(out as *mut u8, b'0' + carry);
    if carry == 0 {
        (out + 1, longer)
    } else {
        (out, longer + 1)
    }
}

/// The larger run of digits less the smaller, most significant first.
unsafe fn difference(a: u32, a_length: u32, b: u32, b_length: u32) -> (u32, u32) {
    let out = alloc(a_length);
    let mut borrow = 0i8;
    for i in 0..a_length {
        let x = digit_from_end(a, a_length, i) as i8;
        let y = digit_from_end(b, b_length, i) as i8;
        let mut held = x - y - borrow;
        if held < 0 {
            held += 10;
            borrow = 1;
        } else {
            borrow = 0;
        }
        core::ptr::write((out + a_length - 1 - i) as *mut u8, b'0' + held as u8);
    }
    (out, a_length)
}

unsafe fn digit_from_end(at: u32, length: u32, back: u32) -> u8 {
    if back >= length {
        0
    } else {
        core::ptr::read((at + length - 1 - back) as *const u8) - b'0'
    }
}

/// Two runs of digits multiplied.
unsafe fn product(a: u32, a_length: u32, b: u32, b_length: u32) -> (u32, u32) {
    let total = a_length + b_length;
    let out = alloc(total);
    core::ptr::write_bytes(out as *mut u8, b'0', total as usize);
    for i in 0..a_length {
        let x = digit_from_end(a, a_length, i) as u32;
        if x == 0 {
            continue;
        }
        let mut carry = 0u32;
        for j in 0..b_length {
            let y = digit_from_end(b, b_length, j) as u32;
            let at = total - 1 - (i + j);
            let held = (core::ptr::read((out + at) as *const u8) - b'0') as u32 + x * y + carry;
            core::ptr::write((out + at) as *mut u8, b'0' + (held % 10) as u8);
            carry = held / 10;
        }
        let mut at = total - 1 - (i + b_length);
        while carry > 0 {
            let held = (core::ptr::read((out + at) as *const u8) - b'0') as u32 + carry;
            core::ptr::write((out + at) as *mut u8, b'0' + (held % 10) as u8);
            carry = held / 10;
            at -= 1;
        }
    }
    (out, total)
}

/// One run of digits divided by another: the quotient's digits and whether anything was left over.
unsafe fn quotient(a: u32, a_length: u32, b: u32, b_length: u32) -> (u32, u32, bool) {
    let out = alloc(a_length);
    let rest = alloc(a_length + 1);
    let mut rest_length = 0u32;
    for i in 0..a_length {
        // Bring the next digit down.
        core::ptr::write((rest + rest_length) as *mut u8, core::ptr::read((a + i) as *const u8));
        rest_length += 1;
        let mut first = 0;
        while first + 1 < rest_length && core::ptr::read((rest + first) as *const u8) == b'0' {
            first += 1;
        }
        let mut times = 0u8;
        while larger(rest + first, rest_length - first, b, b_length) >= 0 {
            let (left, left_length) =
                difference(rest + first, rest_length - first, b, b_length);
            core::ptr::copy_nonoverlapping(
                left as *const u8,
                (rest + first) as *mut u8,
                left_length as usize,
            );
            while first + 1 < rest_length && core::ptr::read((rest + first) as *const u8) == b'0' {
                first += 1;
            }
            times += 1;
        }
        core::ptr::write((out + i) as *mut u8, b'0' + times);
        core::ptr::copy((rest + first) as *const u8, rest as *mut u8, (rest_length - first) as usize);
        rest_length -= first;
    }
    let mut over = false;
    for i in 0..rest_length {
        if core::ptr::read((rest + i) as *const u8) != b'0' {
            over = true;
        }
    }
    (out, a_length, over)
}

/// The sum of two amounts, at the larger of their scales.
#[no_mangle]
pub unsafe extern "C" fn __souther_decimal_add(left: u32, right: u32) -> u32 {
    added(left, right, sign(right))
}

/// The difference of two amounts.
#[no_mangle]
pub unsafe extern "C" fn __souther_decimal_subtract(left: u32, right: u32) -> u32 {
    added(left, right, -sign(right))
}

unsafe fn added(left: u32, right: u32, right_sign: i32) -> u32 {
    if sign(left) == 0 && right_sign == 0 {
        return made(if scale(left) > scale(right) { scale(left) } else { scale(right) }, 0, 0);
    }
    let (a, a_length, b, b_length, held) = aligned(left, right);
    if sign(left) == right_sign || sign(left) == 0 || right_sign == 0 {
        let (out, length) = sum(a, a_length, b, b_length);
        let answering = if sign(left) != 0 { sign(left) } else { right_sign };
        return of_digits(out, length, held, answering < 0);
    }
    let which = larger(a, a_length, b, b_length);
    if which == 0 {
        return made(held, 0, 0);
    }
    let (out, length) = if which > 0 {
        difference(a, a_length, b, b_length)
    } else {
        difference(b, b_length, a, a_length)
    };
    let answering = if which > 0 { sign(left) } else { right_sign };
    of_digits(out, length, held, answering < 0)
}

/// The product of two amounts, at the sum of their scales.
#[no_mangle]
pub unsafe extern "C" fn __souther_decimal_multiply(left: u32, right: u32) -> u32 {
    if sign(left) == 0 || sign(right) == 0 {
        return made(added_scale(left, right), 0, 0);
    }
    let (a, a_length) = digits(left);
    let (b, b_length) = digits(right);
    let (out, length) = product(a, a_length, b, b_length);
    of_digits(out, length, added_scale(left, right), sign(left) != sign(right))
}

unsafe fn added_scale(left: u32, right: u32) -> i32 {
    let held = scale(left) as i64 + scale(right) as i64;
    if held > i32::MAX as i64 || held < i32::MIN as i64 {
        abort(REASON_OUT_OF_RANGE, 0, held as u64, 0);
    }
    held as i32
}

/// The opposite of an amount, which keeps the scale it was written at.
#[no_mangle]
pub unsafe extern "C" fn __souther_decimal_negate(cell: u32) -> u32 {
    if sign(cell) == 0 {
        return cell;
    }
    let (at, length) = digits(cell);
    of_digits(at, length, scale(cell), sign(cell) > 0)
}

/// An amount at a scale, rounded the way a mode says.
///
/// Whether the digits that fall off are worth half is decided by comparing twice what is left with
/// what it was divided by, which is the same question a rounding mode asks of a division.
#[no_mangle]
pub unsafe extern "C" fn __souther_decimal_at_scale(cell: u32, wanted: i32, mode: u32) -> u32 {
    let held = scale(cell);
    if held == wanted {
        return cell;
    }
    if wanted > held {
        let by = (wanted as i64 - held as i64) as u32;
        let (at, length) = digits(cell);
        return of_digits(padded(at, length, by), length + by, wanted, sign(cell) < 0);
    }
    let by = (held as i64 - wanted as i64) as u32;
    let (at, length) = digits(cell);
    if by >= length {
        // Everything falls off, so what is left is nothing and the whole of it decides the rounding.
        let one = alloc(by + 1);
        core::ptr::write(one as *mut u8, b'1');
        core::ptr::write_bytes((one + 1) as *mut u8, b'0', by as usize);
        return rounded(alloc(1), 0, at, length, one, by + 1, wanted, sign(cell) < 0, mode);
    }
    let kept = length - by;
    let one = alloc(by + 1);
    core::ptr::write(one as *mut u8, b'1');
    core::ptr::write_bytes((one + 1) as *mut u8, b'0', by as usize);
    rounded(at, kept, at + kept, by, one, by + 1, wanted, sign(cell) < 0, mode)
}

/// Rounds a quotient: the digits kept, what was left over, and what it was over.
#[allow(clippy::too_many_arguments)]
unsafe fn rounded(
    kept: u32,
    kept_length: u32,
    rest: u32,
    rest_length: u32,
    over: u32,
    over_length: u32,
    wanted: i32,
    negative: bool,
    mode: u32,
) -> u32 {
    let zero_kept = kept_length == 0;
    let (digits_at, digits_length) = if zero_kept {
        let at = alloc(1);
        core::ptr::write(at as *mut u8, b'0');
        (at, 1)
    } else {
        (kept, kept_length)
    };
    let twice = {
        let two = alloc(1);
        core::ptr::write(two as *mut u8, b'2');
        product(rest, rest_length, two, 1)
    };
    let against = larger(twice.0, twice.1, over, over_length);
    let mut any = false;
    for i in 0..rest_length {
        if core::ptr::read((rest + i) as *const u8) != b'0' {
            any = true;
        }
    }
    let up = if !any {
        false
    } else {
        match mode {
            MODE_UP => true,
            MODE_DOWN => false,
            MODE_CEILING => !negative,
            MODE_FLOOR => negative,
            MODE_HALF_UP => against >= 0,
            MODE_HALF_DOWN => against > 0,
            MODE_HALF_EVEN => {
                if against > 0 {
                    true
                } else if against < 0 {
                    false
                } else {
                    (core::ptr::read((digits_at + digits_length - 1) as *const u8) - b'0') % 2 == 1
                }
            }
            // Nothing else is a mode the language declares.
            _ => abort(REASON_OUT_OF_RANGE, 0, mode as u64, 0),
        }
    };
    if !up {
        return of_digits(digits_at, digits_length, wanted, negative);
    }
    let one = alloc(1);
    core::ptr::write(one as *mut u8, b'1');
    let (grown, grown_length) = sum(digits_at, digits_length, one, 1);
    of_digits(grown, grown_length, wanted, negative)
}

/// The rounding modes, in the order the language declares them.
pub const MODE_HALF_UP: u32 = 0;
/// Half to the even digit.
pub const MODE_HALF_EVEN: u32 = 1;
/// Half towards nothing.
pub const MODE_HALF_DOWN: u32 = 2;
/// Away from nothing.
pub const MODE_UP: u32 = 3;
/// Towards nothing.
pub const MODE_DOWN: u32 = 4;
/// Towards the larger.
pub const MODE_CEILING: u32 = 5;
/// Towards the smaller.
pub const MODE_FLOOR: u32 = 6;

/// One amount divided by another at a scale, rounded the way a mode says.
pub unsafe fn divided(left: u32, right: u32, wanted: i32, mode: u32) -> u32 {
    let negative = sign(left) != sign(right);
    if sign(left) == 0 {
        return made(wanted, 0, 0);
    }
    let (a, a_length) = digits(left);
    let (b, b_length) = digits(right);
    // value(a)/value(b) at scale w is round(A * 10^(w + sb - sa) / B), and where that power is
    // negative it is B that grows instead.
    let power = wanted as i64 + scale(right) as i64 - scale(left) as i64;
    let (top, top_length, bottom, bottom_length) = if power >= 0 {
        (padded(a, a_length, power as u32), a_length + power as u32, b, b_length)
    } else {
        (a, a_length, padded(b, b_length, (-power) as u32), b_length + (-power) as u32)
    };
    let (whole, whole_length, _) = quotient(top, top_length, bottom, bottom_length);
    let (times, times_length) = product(whole, whole_length, bottom, bottom_length);
    let (left_over, left_over_length) = difference(top, top_length, times, times_length);
    rounded(
        whole,
        whole_length,
        left_over,
        left_over_length,
        bottom,
        bottom_length,
        wanted,
        negative,
        mode,
    )
}

/// How many significant digits the `/` operator answers with, which matches what a fixed-size
/// decimal on other platforms carries.
const OPERATOR_DIGITS: i64 = 29;

/// The `/` operator on `Decimal`: the quotient to that many significant digits, half away from
/// nothing, and an end to the call on a zero divisor.
#[no_mangle]
pub unsafe extern "C" fn __souther_decimal_divide_by(left: u32, right: u32) -> u32 {
    if sign(right) == 0 {
        abort(crate::REASON_DIVISION_BY_ZERO, 0, 0, 0);
    }
    if sign(left) == 0 {
        return made(0, 0, 0);
    }
    let (_, a_length) = digits(left);
    let (_, b_length) = digits(right);
    // Where the point falls in the quotient, give or take one, which is what says how many places
    // after it make up the significant digits asked for.
    let places = (a_length as i64 - scale(left) as i64) - (b_length as i64 - scale(right) as i64);
    let mut wanted = OPERATOR_DIGITS - places;
    // Exact where it terminates: the same amount written with as few places as it needs, but no
    // fewer than what dividing one scale by the other prefers.
    let preferred = scale(left) as i64 - scale(right) as i64;
    let generous = if wanted > preferred { wanted } else { preferred };
    let held = divided(left, right, bounded(generous + 1), MODE_DOWN);
    if exact(left, right, held) {
        return toward(held, bounded(preferred));
    }
    // Where the point falls was worked out give or take a digit, so the scale is adjusted by
    // however many the answer turned out to be short or over and asked again.
    let mut answering = divided(left, right, bounded(wanted), MODE_HALF_UP);
    for _ in 0..4 {
        let held = precision(answering) as i64;
        if held == OPERATOR_DIGITS {
            break;
        }
        wanted += OPERATOR_DIGITS - held;
        answering = divided(left, right, bounded(wanted), MODE_HALF_UP);
    }
    answering
}

fn bounded(held: i64) -> i32 {
    if held > i32::MAX as i64 || held < i32::MIN as i64 {
        // A scale outside what one can be is a model bug rather than an amount.
        unsafe { abort(REASON_OUT_OF_RANGE, 0, held as u64, 0) }
    }
    held as i32
}

/// Whether an amount times the divisor is the dividend, which is what makes a quotient exact.
unsafe fn exact(left: u32, right: u32, held: u32) -> bool {
    compare(__souther_decimal_multiply(held, right), left) == 0
}

/// The same amount written with as few places as it needs, and no fewer than a scale asks for.
unsafe fn toward(cell: u32, preferred: i32) -> u32 {
    let held = stripped(cell);
    if scale(held) >= preferred {
        return held;
    }
    __souther_decimal_at_scale(held, preferred, MODE_DOWN)
}

/// `Decimal.divide(dividend, divisor, scale, mode)`: the quotient at that scale, or the case a zero
/// divisor is.
///
/// The zero divisor is answered before the scale is looked at. A division that does not run needs
/// no scale to run at, so a call with both a zero divisor and a scale no number holds answers the
/// case the model can handle rather than ending about a number nothing was going to divide.
#[no_mangle]
pub unsafe extern "C" fn __souther_decimal_divide(
    left: u32,
    right: u32,
    wanted: u32,
    mode: u32,
    absent: u32,
) -> u32 {
    if sign(right) == 0 {
        return crate::value::__souther_unit(absent);
    }
    let places = crate::value::__souther_int_value(wanted);
    divided(left, right, bounded(places), mode)
}

/// `Decimal.round(scale, mode, d)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_decimal_round(wanted: u32, mode: u32, cell: u32) -> u32 {
    __souther_decimal_at_scale(cell, bounded(crate::value::__souther_int_value(wanted)), mode)
}

/// `Decimal.toInt(mode, d)`: the whole number it rounds to, and an end to the call where that is
/// not a number an `Int` holds.
#[no_mangle]
pub unsafe extern "C" fn __souther_decimal_to_int(mode: u32, cell: u32) -> u32 {
    let whole = __souther_decimal_at_scale(cell, 0, mode);
    let (at, length) = digits(whole);
    let mut magnitude: u128 = 0;
    for i in 0..length {
        magnitude = magnitude * 10 + (core::ptr::read((at + i) as *const u8) - b'0') as u128;
        if magnitude > 1u128 << 63 {
            abort(crate::REASON_INT_OVERFLOW, 0, length as u64, 0);
        }
    }
    let limit = if sign(whole) < 0 { 1u128 << 63 } else { i64::MAX as u128 };
    if magnitude > limit {
        abort(crate::REASON_INT_OVERFLOW, 0, length as u64, 0);
    }
    crate::value::__souther_int(if sign(whole) < 0 {
        (magnitude as i128).wrapping_neg() as i64
    } else {
        magnitude as i64
    })
}

/// `Decimal.fromInt(n)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_decimal_from_int(cell: u32) -> u32 {
    let held = crate::value::__souther_int_value(cell);
    let written = crate::json::__souther_json_write_int(held);
    let at = written as u32;
    let length = (written >> 32) as u32;
    if held < 0 {
        of_digits(at + 1, length - 1, 0, true)
    } else {
        of_digits(at, length, 0, false)
    }
}

/// `Decimal.compare(a, b)`: minus one, zero or one, by what the two are worth.
#[no_mangle]
pub unsafe extern "C" fn __souther_decimal_compare(left: u32, right: u32) -> u32 {
    crate::value::__souther_int(compare(left, right) as i64)
}
