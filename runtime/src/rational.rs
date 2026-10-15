//! A `Rational`: an exact quotient, and where one is kept.
//!
//! What a value is and what each operation on one answers is `souther_exact`'s, which the native
//! runtime answers from too, so the two backends give one answer by having one source and not by
//! being held to each other. What is here is the cell a value is kept in, how a `Decimal` and an
//! `Int` are read into one and back out, and what a failure ends the call as.
//!
//! ```text
//! +0   u32 tag
//! +4   i64 the power of two
//! +12  i64 the power of five
//! +20  i32 how many bytes the numerator is, negative where the value is
//! +24  u32 how many bytes the denominator is
//! +28  the numerator's bytes and then the denominator's, little end first, no zero byte at the top
//! ```
//!
//! The value is `numerator × 2^twos × 5^fives / denominator` in the one form each value has, so two
//! cells holding one value hold the same parts and a collection can tell them for one.
//!
//! A failure is one of two, and they end a call two ways. An answer with no place is the language's
//! `REQUIRED_FORM_HAS_NO_PLACE`, as an `Int` that overflows is. The run having no room for what an
//! order or a rounding needed to know is no answer at all: it is the platform failing, as an arena
//! that cannot grow is, and ends the call as one.

use crate::decimal;
use crate::value::{
    __souther_int, __souther_int_value, __souther_list_get, __souther_list_length, __souther_unit,
    TAG_RATIONAL,
};
use crate::{
    abort, alloc, REASON_DIVISION_BY_ZERO, REASON_OUT_OF_MEMORY,
    REASON_REQUIRED_FORM_HAS_NO_PLACE,
};
use core::cmp::Ordering;
use souther_exact::{Exact, Failure, Magnitude, Ratio};

const OFF_TWOS: u32 = 4;
const OFF_FIVES: u32 = 12;
const OFF_NUMERATOR: u32 = 20;
const OFF_DENOMINATOR: u32 = 24;
const HEADER: u32 = 28;

unsafe fn read_u32(at: u32) -> u32 {
    core::ptr::read_unaligned(at as usize as *const u32)
}

unsafe fn read_i64(at: u32) -> i64 {
    core::ptr::read_unaligned(at as usize as *const i64)
}

unsafe fn write_u32(at: u32, word: u32) {
    core::ptr::write_unaligned(at as usize as *mut u32, word);
}

unsafe fn write_i64(at: u32, word: i64) {
    core::ptr::write_unaligned(at as usize as *mut i64, word);
}

/// The answer, or the end of the call for the reason it has none.
unsafe fn settled<T>(answer: Exact<T>) -> T {
    match answer {
        Ok(answer) => answer,
        Err(Failure::NoPlace) => abort(REASON_REQUIRED_FORM_HAS_NO_PLACE, 0, 0, 0),
        Err(Failure::NoRoom) => abort(REASON_OUT_OF_MEMORY, 0, 0, 0),
    }
}

/// A cell holding this value.
unsafe fn cell_of(ratio: &Ratio) -> u32 {
    let (negative, numerator, denominator, twos, fives) = ratio.parts();
    numerator.with_le_bytes(|numerator| {
        denominator.with_le_bytes(|denominator| {
            let (above, below) = (numerator.len() as u32, denominator.len() as u32);
            let cell = alloc(HEADER + above + below);
            write_u32(cell, TAG_RATIONAL);
            write_i64(cell + OFF_TWOS, twos);
            write_i64(cell + OFF_FIVES, fives);
            write_u32(
                cell + OFF_NUMERATOR,
                if negative { (above as i32).wrapping_neg() as u32 } else { above },
            );
            write_u32(cell + OFF_DENOMINATOR, below);
            core::ptr::copy_nonoverlapping(
                numerator.as_ptr(),
                (cell + HEADER) as usize as *mut u8,
                numerator.len(),
            );
            core::ptr::copy_nonoverlapping(
                denominator.as_ptr(),
                (cell + HEADER + above) as usize as *mut u8,
                denominator.len(),
            );
            cell
        })
    })
}

/// The value a cell holds.
pub(crate) unsafe fn ratio(cell: u32) -> Ratio {
    let signed = read_u32(cell + OFF_NUMERATOR) as i32;
    let above = signed.unsigned_abs();
    let below = read_u32(cell + OFF_DENOMINATOR);
    let bytes = |from: u32, length: u32| {
        core::slice::from_raw_parts((cell + HEADER + from) as usize as *const u8, length as usize)
    };
    // Trusted because every cell of one was written by `cell_of` from the parts of a value.
    Ratio::from_trusted_parts(
        signed < 0,
        Magnitude::of_le_bytes(bytes(0, above)),
        Magnitude::of_le_bytes(bytes(above, below)),
        read_i64(cell + OFF_TWOS),
        read_i64(cell + OFF_FIVES),
    )
}

/// Where one stands against another by exact value, whatever their exponents: what `==`, `<`, a set
/// and a sort ask.
///
/// Reached through the slot a descriptor of one holds (`descriptor::KIND_RATIONAL`), so it is
/// exported for the compiler to put there and is called by nothing here.
#[no_mangle]
pub unsafe extern "C" fn __souther_rational_order(left: u32, right: u32) -> i32 {
    match settled(ratio(left).compare(&ratio(right))) {
        Ordering::Less => -1,
        Ordering::Equal => 0,
        Ordering::Greater => 1,
    }
}

/// The bytes the parts of a value are, run by run, for a hash: two cells holding one value hold the
/// same parts. Read off the cell, so a hash of one carries no arithmetic with it.
pub(crate) unsafe fn parts(cell: u32) -> (u32, u32) {
    let above = (read_u32(cell + OFF_NUMERATOR) as i32).unsigned_abs();
    let below = read_u32(cell + OFF_DENOMINATOR);
    (cell + OFF_TWOS, HEADER - OFF_TWOS + above + below)
}


/// `Rational.fromInt`, and an `Int` read at its exact value by an operator with a `Rational` on the
/// other side.
#[no_mangle]
pub unsafe extern "C" fn __souther_rational_from_int(cell: u32) -> u32 {
    cell_of(&Ratio::of_int(__souther_int_value(cell)))
}

/// `Rational.fromDecimal`, and a `Decimal` read at its exact value by an operator. The scale goes to
/// the exponents and nothing is built from it.
#[no_mangle]
pub unsafe extern "C" fn __souther_rational_from_decimal(cell: u32) -> u32 {
    cell_of(&Ratio::of_decimal(&decimal::amount(cell)))
}

/// The unary `-`.
#[no_mangle]
pub unsafe extern "C" fn __souther_rational_negate(cell: u32) -> u32 {
    cell_of(&ratio(cell).negated())
}

/// `+` and `Rational.add`.
#[no_mangle]
pub unsafe extern "C" fn __souther_rational_add(left: u32, right: u32) -> u32 {
    cell_of(&settled(ratio(left).add(&ratio(right))))
}

/// `-` and `Rational.subtract`.
#[no_mangle]
pub unsafe extern "C" fn __souther_rational_subtract(left: u32, right: u32) -> u32 {
    cell_of(&settled(ratio(left).subtract(&ratio(right))))
}

/// `*` and `Rational.multiply`.
#[no_mangle]
pub unsafe extern "C" fn __souther_rational_multiply(left: u32, right: u32) -> u32 {
    cell_of(&settled(ratio(left).multiply(&ratio(right))))
}

/// `/` and `Rational.divide`: the exact quotient, and an end to the call on a zero divisor, which
/// an exact quotient has no case for.
#[no_mangle]
pub unsafe extern "C" fn __souther_rational_divide(left: u32, right: u32) -> u32 {
    let divisor = ratio(right);
    if divisor.is_zero() {
        abort(REASON_DIVISION_BY_ZERO, 0, 0, 0);
    }
    cell_of(&settled(ratio(left).divide(&divisor)))
}

/// `Rational.compare`: minus one, nought or one, by exact value.
#[no_mangle]
pub unsafe extern "C" fn __souther_rational_compare(left: u32, right: u32) -> u32 {
    __souther_int(i64::from(__souther_rational_order(left, right)))
}

/// `List.sum` over exact quotients, which nought is the sum of none of.
///
/// An entry of its own rather than an arm of `List.sum`, because that one is reached by every
/// program totalling anything and would carry exact arithmetic into all of them.
#[no_mangle]
pub unsafe extern "C" fn __souther_rational_sum(list: u32) -> u32 {
    let mut total = Ratio::ZERO;
    for i in 0..__souther_list_length(list) {
        total = settled(total.add(&ratio(__souther_list_get(list, i))));
    }
    cell_of(&total)
}

/// `List.product` over exact quotients, which one is the product of none of, as `List.sum`.
#[no_mangle]
pub unsafe extern "C" fn __souther_rational_product(list: u32) -> u32 {
    let mut total = Ratio::of_int(1);
    for i in 0..__souther_list_length(list) {
        total = settled(total.multiply(&ratio(__souther_list_get(list, i))));
    }
    cell_of(&total)
}

/// `Rational.toWholeNumber`: the `Int` it is, or `NotWhole` where it has a fraction. A whole number
/// past every `Int` has no place, which is not the case that says it has a fraction.
#[no_mangle]
pub unsafe extern "C" fn __souther_rational_to_whole_number(cell: u32, not_whole: u32) -> u32 {
    let value = ratio(cell);
    if !value.is_whole() {
        return __souther_unit(not_whole);
    }
    __souther_int(settled(value.to_whole()))
}

/// `Rational.toFiniteDecimal`: the `Decimal` it is, or `NotAFiniteDecimal` where it repeats.
#[no_mangle]
pub unsafe extern "C" fn __souther_rational_to_finite_decimal(cell: u32, repeating: u32) -> u32 {
    let value = ratio(cell);
    if !value.has_finite_decimal() {
        return __souther_unit(repeating);
    }
    decimal::cell_of(&settled(value.to_finite_decimal()))
}

/// `Rational.toInt(mode, r)`: the whole number it rounds to by the mode.
#[no_mangle]
pub unsafe extern "C" fn __souther_rational_to_int(mode: u32, cell: u32) -> u32 {
    __souther_int(settled(ratio(cell).to_int(decimal::rounding(mode))))
}

/// `Rational.toDecimal(scale, mode, r)`: the value at that many places, rounded by the mode.
#[no_mangle]
pub unsafe extern "C" fn __souther_rational_to_decimal(scale: u32, mode: u32, cell: u32) -> u32 {
    let places = __souther_int_value(scale);
    decimal::cell_of(&settled(ratio(cell).to_decimal(places, decimal::rounding(mode))))
}
