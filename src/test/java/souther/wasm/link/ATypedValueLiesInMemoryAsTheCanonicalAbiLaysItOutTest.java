package souther.wasm.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import souther.wasm.link.CanonicalAbi.Flat;

/**
 * Where a typed value's parts lie, worked out by hand from CanonicalABI.md for the forms whose
 * layout is least like a struct's: fields padded to what follows them, a variant whose payload
 * is aligned to its widest case, a discriminant that grows past 256 cases, and the result every
 * offered function answers.
 *
 * <p>The flattening of whole functions is held to {@code wasm-tools} elsewhere
 * ({@code ATypedFunctionIsTheCoreFunctionTheCanonicalAbiFlattensItToTest}); what a signature does
 * not show is where in memory each part is, and this is where that is asked.
 */
class ATypedValueLiesInMemoryAsTheCanonicalAbiLaysItOutTest {

    private static final WitType.Named RECORD = new WitType.Named("m", "padded");
    private static final WitType.Named VARIANT = new WitType.Named("m", "joined");
    private static final WitType.Named WIDE = new WitType.Named("m", "wide");

    private static final CanonicalAbi ABI = CanonicalAbi.of(new TypedOffering(
            Map.of("m", List.of(
                    new WitType.Record("padded", List.of(
                            new WitType.Field("a", WitType.BOOL),
                            new WitType.Field("b", WitType.S64),
                            new WitType.Field("c", WitType.STRING))),
                    new WitType.Variant("joined", List.of(
                            new WitType.Case("a", null),
                            new WitType.Case("b", WitType.S64),
                            new WitType.Case("c", WitType.STRING))),
                    new WitType.Enumeration("wide", cases(300)))),
            Map.of(), Map.of(), Map.of()));

    @Test
    void padsAFieldToWhatItHolds() {
        assertThat(ABI.offsets(RECORD)).containsExactly(0, 8, 16);
        assertThat(ABI.size(RECORD)).isEqualTo(24);
        assertThat(ABI.alignment(RECORD)).isEqualTo(8);
        assertThat(ABI.flatten(RECORD)).containsExactly(Flat.I32, Flat.I64, Flat.I32, Flat.I32);
    }

    @Test
    void alignsAVariantsPayloadToItsWidestCaseAndJoinsWhatItsCasesFlattenTo() {
        assertThat(ABI.payloadOffset(VARIANT)).isEqualTo(8);
        assertThat(ABI.size(VARIANT)).isEqualTo(16);
        assertThat(ABI.alignment(VARIANT)).isEqualTo(8);
        // b's i64 and c's first i32 share a place, which is then an i64.
        assertThat(ABI.flatten(VARIANT)).containsExactly(Flat.I32, Flat.I64, Flat.I32);
    }

    @Test
    void saysWhichOfMoreThan256CasesAValueIsInTwoBytes() {
        assertThat(ABI.size(WIDE)).isEqualTo(2);
        assertThat(ABI.alignment(WIDE)).isEqualTo(2);
        assertThat(ABI.flatten(WIDE)).containsExactly(Flat.I32);
    }

    @Test
    void laysAnOptionOutAsAVariantOfNothingAndItsValue() {
        WitType option = new WitType.OptionOf(WitType.BOOL);
        assertThat(ABI.size(option)).isEqualTo(2);
        assertThat(ABI.payloadOffset(option)).isEqualTo(1);
        assertThat(ABI.flatten(option)).containsExactly(Flat.I32, Flat.I32);
    }

    /**
     * {@code ended} is a u32 and an option of a record of two strings, twenty-four bytes at four;
     * a refusal carries it after a byte padded to four, twenty-eight; and the result an answer of
     * an {@code s64} is carries that after a byte padded to eight, forty.
     */
    @Test
    void laysOutTheResultEveryOfferedFunctionAnswers() {
        WitType ended = new WitType.Named(null, TypedOffering.ENDED);
        WitType refusal = new WitType.Named(null, TypedOffering.REFUSAL);
        WitType answered = new WitType.ResultOf(WitType.S64, refusal);

        assertThat(ABI.offsets(ended)).containsExactly(0, 4);
        assertThat(ABI.size(ended)).isEqualTo(24);
        assertThat(ABI.size(refusal)).isEqualTo(28);
        assertThat(ABI.payloadOffset(refusal)).isEqualTo(4);
        assertThat(ABI.payloadOffset(answered)).isEqualTo(8);
        assertThat(ABI.size(answered)).isEqualTo(40);
        assertThat(ABI.lifted(List.of(WitType.S64), answered).toString())
                .isEqualTo("(param i64) (result i32)");
        assertThat(ABI.lowered(List.of(WitType.S64), answered).toString())
                .isEqualTo("(param i64 i32)");
    }

    private static List<String> cases(int count) {
        List<String> cases = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            cases.add("c" + i);
        }
        return cases;
    }
}
