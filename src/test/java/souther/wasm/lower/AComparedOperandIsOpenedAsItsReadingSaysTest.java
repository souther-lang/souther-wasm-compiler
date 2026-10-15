package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.wasm.Compiled;
import souther.wasm.Running;

/**
 * How far each operand of a comparison is opened is what the checker's reading says, and not
 * something worked out here from the operand's type.
 *
 * <p>Read in a type, neither side is opened: a {@code Name} beside the {@code Key} listing it is
 * that {@code Key}. Opened beside a literal, the newtype's side is opened through every name it
 * wears, whichever side it is written on. Read as they stand, both are, and two newtypes over an
 * enumeration are placed by it.
 */
class AComparedOperandIsOpenedAsItsReadingSaysTest {

    private static final String SOURCE = """
            module reading

            data Inner = Int
            data Code = Inner
            data Name = String
            data Key = Code | Name

            data Low
            data High
            data Level = Low | High
            data LevelN = Level

            behavior inKey : (k: Key) -> List<Bool>

            let inKey (k) = [
                k == Name("a"),
                Name("a") == k,
                k /= Name("a"),
                k == Code(Inner(7)),
                Code(Inner(7)) == k
            ]

            behavior besideALiteral : (c: Code, n: Name) -> List<Bool>

            let besideALiteral (c, n) = [7 == c, c == 7, c <= 7, 8 > c, n == "a", "a" == n]

            behavior asTheyStand : (c: Code, d: Code, l: LevelN, m: LevelN) -> List<Bool>

            let asTheyStand (c, d, l, m) = [c == d, c < d, l < m, l == m]
            """;

    @Test
    void aNewtypeCaseBesideItsSumIsTheValueOfTheSumItIs() {
        Running module = compiled();

        assertThat(answerOf(module, "reading.inKey", "[{\"type\":\"Name\",\"value\":\"a\"}]"))
                .isEqualTo("{\"value\":[true,true,false,false,false]}");
        assertThat(answerOf(module, "reading.inKey", "[{\"type\":\"Code\",\"value\":7}]"))
                .isEqualTo("{\"value\":[false,false,true,true,true]}");
        assertThat(answerOf(module, "reading.inKey", "[{\"type\":\"Name\",\"value\":\"b\"}]"))
                .isEqualTo("{\"value\":[false,false,true,false,false]}");
    }

    @Test
    void aNewtypeBesideALiteralIsOpenedOnWhicheverSideItIsWritten() {
        Running module = compiled();

        assertThat(answerOf(module, "reading.besideALiteral", "[7, \"a\"]"))
                .isEqualTo("{\"value\":[true,true,true,true,true,true]}");
        assertThat(answerOf(module, "reading.besideALiteral", "[9, \"b\"]"))
                .isEqualTo("{\"value\":[false,false,false,false,false,false]}");
    }

    @Test
    void twoOfOneTypeAreOpenedThroughEveryNameTheyWear() {
        Running module = compiled();

        assertThat(answerOf(module, "reading.asTheyStand", "[3, 4, \"Low\", \"High\"]"))
                .isEqualTo("{\"value\":[false,true,true,false]}");
        assertThat(answerOf(module, "reading.asTheyStand", "[4, 4, \"High\", \"High\"]"))
                .isEqualTo("{\"value\":[true,false,false,true]}");
    }

    private static Running compiled() {
        return Running.linked(Compiled.module(Compiled.program(List.of(SOURCE))));
    }

    private static String answerOf(Running module, String export, String arguments) {
        int address = module.staged(arguments);
        long[] answer = module.callWithString(
                export, address, arguments.getBytes(StandardCharsets.UTF_8).length);
        return new String(module.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8);
    }
}
