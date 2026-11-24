package souther.wasm.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.wasm.Compiled;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;

/**
 * The core functions a typed component lifts, run: handed core values as the Canonical ABI hands a
 * typed function's arguments, and read back where the ABI reads a result.
 *
 * <p>Where each part lies is worked out here by hand from CanonicalABI.md and not asked of the
 * compiler, so a layout the compiler got wrong is read here as the wrong value rather than
 * agreeing with itself.
 */
class ATypedCallIsReadAndAnsweredWhereTheCanonicalAbiLaysItTest {

    private static final String TRIP = """
            module trip exposing ( EmployeeId, DraftRequest, Submitted, Rejected, submit, share )

            data EmployeeId = String
                invariant String.length(value) > 0

            data DraftRequest = { applicant: EmployeeId, plannedCost: Int }
            data Submitted = { applicant: EmployeeId, plannedCost: Int }
            data Rejected = { reason: String }

            behavior submit : (request: DraftRequest) -> Submitted | Rejected

            let submit (request) =
                if request.plannedCost > 100
                then Rejected { reason = "too much" }
                else Submitted { applicant = request.applicant, plannedCost = request.plannedCost }

            behavior share : (n: Int, d: Int) -> Bool

            let share (n, d) = n / d == n / d
            """;

    @Test
    void readsARecordFlattenedIntoCoreValuesAndAnswersAVariantInMemory() {
        Running module = module();
        int applicant = module.staged("alice");

        int area = (int) module.callWith(Component.Lifted.typed("trip.submit"),
                applicant, 5, 50L)[0];

        // result<submit-result, refusal>: the case, then at eight the submit-result, whose case
        // is followed at eight by the submitted record: the applicant's text, then the cost. The
        // checker orders a union's cases, so `rejected` is the first and `submitted` the second.
        assertThat(area % 8).describedAs("where a result begins").isZero();
        assertThat(byteAt(module, area)).describedAs("ok").isZero();
        assertThat(byteAt(module, area + 8)).describedAs("submitted").isEqualTo(1);
        assertThat(textAt(module, area + 16)).isEqualTo("alice");
        assertThat(longAt(module, area + 24)).isEqualTo(50L);

        area = (int) module.callWith(Component.Lifted.typed("trip.submit"),
                module.staged("bob"), 3, 500L)[0];
        assertThat(byteAt(module, area + 8)).describedAs("rejected").isZero();
        assertThat(textAt(module, area + 16)).isEqualTo("too much");
    }

    @Test
    void refusesWhatBreaksARuleAsIssuesNamingWhereInTheArgumentsItIs() {
        Running module = module();

        int area = (int) module.callWith(Component.Lifted.typed("trip.submit"),
                module.staged(""), 0, 50L)[0];

        // err at eight: a refusal, whose case says issues, and at four the list of them.
        assertThat(byteAt(module, area)).describedAs("err").isEqualTo(1);
        assertThat(byteAt(module, area + 8)).describedAs("issues").isZero();
        int issues = wordAt(module, area + 12);
        assertThat(wordAt(module, area + 16)).describedAs("how many").isEqualTo(1);
        // An issue: path, code, message key, meta, each a string, then an option of a message.
        assertThat(textAt(module, issues)).isEqualTo("/0/applicant");
        assertThat(textAt(module, issues + 8)).isEqualTo("too_short");
    }

    @Test
    void answersACallTheRuntimeEndedAsTheErrOfItsResultAndAnswersTheNextOne() {
        Running module = module();
        int mark = module.call(RuntimeAbi.ALLOC_MARK);

        // result<bool, refusal>: a bool lies at one, and a refusal at four, so the err does.
        int area = (int) module.callWith(Component.Lifted.typed("trip.share"), 1L, 0L)[0];
        assertThat(byteAt(module, area)).describedAs("err").isEqualTo(1);
        assertThat(byteAt(module, area + 4)).describedAs("ended").isEqualTo(1);
        assertThat(wordAt(module, area + 8)).describedAs("a division by zero").isEqualTo(6);
        module.runWith(Component.Lifted.POST_RETURN, area);

        area = (int) module.callWith(Component.Lifted.typed("trip.share"), 4L, 2L)[0];
        assertThat(byteAt(module, area)).describedAs("ok").isZero();
        assertThat(byteAt(module, area + 4)).describedAs("true").isEqualTo(1);
        module.runWith(Component.Lifted.POST_RETURN, area);
        assertThat(module.call(RuntimeAbi.ALLOC_MARK))
                .describedAs("what every call made, given back").isLessThanOrEqualTo(mark);
    }

    @Test
    void readsAValueOfATypeOnItsOwnAndChecksItsRules() {
        Running module = module();

        int area = (int) module.callWith(Component.Lifted.typedReading("trip.EmployeeId"),
                module.staged("carol"), 5)[0];
        assertThat(byteAt(module, area)).describedAs("ok").isZero();
        assertThat(textAt(module, area + 4)).isEqualTo("carol");

        area = (int) module.callWith(Component.Lifted.typedReading("trip.EmployeeId"),
                module.staged(""), 0)[0];
        assertThat(byteAt(module, area)).describedAs("err").isEqualTo(1);
        int issues = wordAt(module, area + 8);
        assertThat(textAt(module, issues)).describedAs("at the value's root").isEmpty();
    }

    private static Running module() {
        return Running.linked(Compiled.typedCore(Compiled.program(List.of(TRIP))));
    }

    private static int byteAt(Running module, int at) {
        return module.read(at, 1)[0] & 0xff;
    }

    private static int wordAt(Running module, int at) {
        return ByteBuffer.wrap(module.read(at, 4)).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static long longAt(Running module, int at) {
        return ByteBuffer.wrap(module.read(at, 8)).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    private static String textAt(Running module, int at) {
        return new String(module.read(wordAt(module, at), wordAt(module, at + 4)),
                StandardCharsets.UTF_8);
    }
}
