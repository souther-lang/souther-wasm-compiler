//! A `Decimal`: where one is kept, how text is read into one, and what a refusal ends the call as.
//!
//! What a value is and what each operation on one answers is `souther_exact`'s [`Amount`], which
//! the native runtime answers from too, so the two backends give one answer by having one source
//! and not by being held to each other. That includes where an answer has no place: an operation
//! whose whole number would be wider than a `Decimal` holds answers nothing, before building it,
//! and the call ends as `REQUIRED_FORM_HAS_NO_PLACE`, as it does on the JVM and in the native
//! runtime.
//!
//! ```text
//! +0   u32 tag
//! +4   i32 scale
//! +8   i32 how many bytes the whole number is, negative where the value is
//! +12  the whole number's bytes, little end first, no zero byte at the top; none for nought
//! ```

use crate::value::TAG_DECIMAL;
use crate::{abort, alloc, REASON_BACKEND_INVARIANT_BROKEN, REASON_REQUIRED_FORM_HAS_NO_PLACE};
use heap::string::String;
use souther_exact::{Amount, Rounding};

const OFF_SCALE: u32 = 4;
const OFF_SIGNED_LENGTH: u32 = 8;
const HEADER: u32 = 12;

unsafe fn read_u32(at: u32) -> u32 {
    core::ptr::read_unaligned(at as usize as *const u32)
}

unsafe fn write_u32(at: u32, word: u32) {
    core::ptr::write_unaligned(at as usize as *mut u32, word);
}

/// A cell holding this value.
pub unsafe fn cell_of(amount: &Amount) -> u32 {
    amount.with_parts(|negative, magnitude, scale| {
        let length = magnitude.len() as u32;
        let cell = alloc(HEADER + length);
        write_u32(cell, TAG_DECIMAL);
        write_u32(cell + OFF_SCALE, scale as u32);
        write_u32(
            cell + OFF_SIGNED_LENGTH,
            if negative { (length as i32).wrapping_neg() as u32 } else { length },
        );
        core::ptr::copy_nonoverlapping(
            magnitude.as_ptr(),
            (cell + HEADER) as usize as *mut u8,
            magnitude.len(),
        );
        cell
    })
}

/// The value a cell holds.
pub unsafe fn amount(cell: u32) -> Amount {
    let signed = read_u32(cell + OFF_SIGNED_LENGTH) as i32;
    let bytes = core::slice::from_raw_parts(
        (cell + HEADER) as usize as *const u8,
        signed.unsigned_abs() as usize,
    );
    // Trusted because every cell of one was written by `cell_of` from the parts of a value.
    Amount::from_trusted_parts(signed < 0, bytes, read_u32(cell + OFF_SCALE) as i32)
}

/// A cell holding the answer, or the end of the call where it has no place: a scale past the
/// 32-bit range, or a whole number wider than a `Decimal` holds.
unsafe fn settled(answer: Option<Amount>) -> u32 {
    match answer {
        Some(amount) => cell_of(&amount),
        None => abort(REASON_REQUIRED_FORM_HAS_NO_PLACE, 0, 0, 0),
    }
}

/// Where text the arena holds is, and how long it is. The arena gives nothing back one allocation
/// at a time, so the text stays where it is until the call's arena is popped.
fn kept(text: String) -> (u32, u32) {
    let held = text.leak();
    (held.as_ptr() as usize as u32, held.len() as u32)
}

/// Reads a number as it was written into the amount it names, at the scale its spelling gives it.
///
/// The text is a JSON number, or one with a `+` in front: a `+` or `-`, one or more digits,
/// optionally a point and one or more digits, and optionally an exponent. That is everything a
/// caller hands here — a literal the compiler wrote, a document's number, decimal text, a bound a
/// clause states — each of which its caller has held to that shape or a narrower one.
///
/// Answers zero where the text is not of that shape, or names a scale or a whole number a `Decimal`
/// has no room for — which is what a reader of a document has to be told rather than ended for. A
/// document's number is as long as the document, so its digits are held to the widest whole number
/// as any other `Decimal`'s are, and refused from their count before they are read. This one function
/// is called from places whose provenance is not the same (`String.toDecimal`, which the language
/// declares never aborts; the JSON boundary decoder, which reports a bad document as an issue and
/// not an abort; and a `Decimal` literal a body wrote down, which the checker settles is always one
/// of these bytes read back, so a zero there is this compiler's own bug and not the language's) —
/// so the choice of what "could not be read" becomes belongs to whichever of those is calling, and
/// this stays total instead of making that choice on their behalf.
pub unsafe fn parse(at: u32, length: u32) -> u32 {
    let text = core::slice::from_raw_parts(at as usize as *const u8, length as usize);
    let unsigned = match text.first() {
        Some(b'+') => &text[1..],
        _ => text,
    };
    if !is_number(unsigned) {
        return 0;
    }
    match Amount::of_json_number(unsigned) {
        Some(amount) => cell_of(&amount),
        None => 0,
    }
}

/// Whether the text is a JSON number, with the one difference that a leading zero is allowed: an
/// optional `-`, one or more digits, optionally a point and one or more digits, and optionally an
/// `e` or `E`, a sign, and one or more digits.
fn is_number(text: &[u8]) -> bool {
    let digits = |from: usize| text[from..].iter().take_while(|it| it.is_ascii_digit()).count();
    let mut at = usize::from(text.first() == Some(&b'-'));
    let whole = digits(at);
    if whole == 0 {
        return false;
    }
    at += whole;
    if text.get(at) == Some(&b'.') {
        let fraction = digits(at + 1);
        if fraction == 0 {
            return false;
        }
        at += 1 + fraction;
    }
    if matches!(text.get(at), Some(b'e' | b'E')) {
        at += 1;
        if matches!(text.get(at), Some(b'+' | b'-')) {
            at += 1;
        }
        let exponent = digits(at);
        if exponent == 0 {
            return false;
        }
        at += exponent;
    }
    at == text.len()
}

/// The value at the scale it carries, as the JVM's `BigDecimal.toString` writes it: what an issue's
/// metadata says a `Decimal` is.
pub unsafe fn written(cell: u32) -> (u32, u32) {
    kept(amount(cell).scaled_text())
}

/// The text the value is written as at a boundary: the one form of its amount, so that two ways
/// of writing it are one document and hash alike.
pub unsafe fn external(cell: u32) -> (u32, u32) {
    kept(amount(cell).external_text())
}

/// `String.fromDecimal`'s text: plain notation at the scale the value carries, and the end of the
/// call where that is longer than a string holds, which is decided before any of it is written.
pub unsafe fn written_in_full(cell: u32) -> (u32, u32) {
    match amount(cell).plain_text(crate::notation::LONGEST_TEXT as i64) {
        Some(text) => kept(text),
        None => abort(REASON_REQUIRED_FORM_HAS_NO_PLACE, 0, 0, 0),
    }
}

/// Where one amount stands relative to another, by what they are worth and not by how written.
pub unsafe fn compare(left: u32, right: u32) -> i32 {
    amount(left).compare(&amount(right)) as i32
}

/// The sum of two amounts, at the larger of their scales.
#[no_mangle]
pub unsafe extern "C" fn __souther_decimal_add(left: u32, right: u32) -> u32 {
    settled(amount(left).add(&amount(right)))
}

/// The difference of two amounts.
#[no_mangle]
pub unsafe extern "C" fn __souther_decimal_subtract(left: u32, right: u32) -> u32 {
    settled(amount(left).subtract(&amount(right)))
}

/// The product of two amounts, at the sum of their scales.
#[no_mangle]
pub unsafe extern "C" fn __souther_decimal_multiply(left: u32, right: u32) -> u32 {
    settled(amount(left).multiply(&amount(right)))
}

/// The opposite of an amount, which keeps the scale it was written at.
#[no_mangle]
pub unsafe extern "C" fn __souther_decimal_negate(cell: u32) -> u32 {
    cell_of(&amount(cell).negated())
}

/// An amount at a scale, rounded the way a mode says.
#[no_mangle]
pub unsafe extern "C" fn __souther_decimal_at_scale(cell: u32, wanted: i32, mode: u32) -> u32 {
    settled(amount(cell).round(i64::from(wanted), rounding(mode)))
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

/// Which mode a `RoundingMode` is, from its place among the cases the language declares, which is
/// what the compiler passes (`CASE_OF`). The one place an ordinal is read, for an amount's
/// rounding and a `Rational`'s alike.
pub unsafe fn rounding(mode: u32) -> Rounding {
    match mode {
        MODE_HALF_UP => Rounding::HalfUp,
        MODE_HALF_EVEN => Rounding::HalfEven,
        MODE_HALF_DOWN => Rounding::HalfDown,
        MODE_UP => Rounding::Up,
        MODE_DOWN => Rounding::Down,
        MODE_CEILING => Rounding::Ceiling,
        MODE_FLOOR => Rounding::Floor,
        // Nothing else is a mode the language declares: every `RoundingMode` case this module was
        // compiled against is above, so an ordinal outside them is the compiler and this crate
        // disagreeing about what the language declares, not a Souther program failing to hold
        // anything.
        _ => abort(REASON_BACKEND_INVARIANT_BROKEN, 0, mode as u64, 0),
    }
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
    let divisor = amount(right);
    if divisor.is_zero() {
        return crate::value::__souther_unit(absent);
    }
    let places = crate::value::__souther_int_value(wanted);
    settled(amount(left).divide(&divisor, places, rounding(mode)))
}

/// `Decimal.round(scale, mode, d)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_decimal_round(wanted: u32, mode: u32, cell: u32) -> u32 {
    let places = crate::value::__souther_int_value(wanted);
    settled(amount(cell).round(places, rounding(mode)))
}

/// `Decimal.toInt(mode, d)`: the whole number it rounds to, and an end to the call where that is
/// not a number an `Int` holds.
#[no_mangle]
pub unsafe extern "C" fn __souther_decimal_to_int(mode: u32, cell: u32) -> u32 {
    match amount(cell).to_int(rounding(mode)) {
        Some(whole) => crate::value::__souther_int(whole),
        None => abort(REASON_REQUIRED_FORM_HAS_NO_PLACE, 0, 0, 0),
    }
}

/// `Decimal.fromInt(n)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_decimal_from_int(cell: u32) -> u32 {
    cell_of(&Amount::of_int(crate::value::__souther_int_value(cell)))
}

/// `Decimal.compare(a, b)`: minus one, zero or one, by what the two are worth.
#[no_mangle]
pub unsafe extern "C" fn __souther_decimal_compare(left: u32, right: u32) -> u32 {
    crate::value::__souther_int(compare(left, right) as i64)
}
