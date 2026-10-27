package io.github.tortaje.fitnesse.health;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonTest {

    @Test
    void writesScalars() {
        assertThat(Json.write(null)).isEqualTo("null");
        assertThat(Json.write(true)).isEqualTo("true");
        assertThat(Json.write(12)).isEqualTo("12");
        assertThat(Json.write(1.5)).isEqualTo("1.5");
    }

    @Test
    void escapesStrings() {
        assertThat(Json.write("a\"b\\c\n\t\u0001é")).isEqualTo("\"a\\\"b\\\\c\\n\\t\\u0001é\"");
    }

    @Test
    void keepsMapIterationOrder() {
        var m = new LinkedHashMap<String, Object>();
        m.put("b", 1);
        m.put("a", List.of("x", 2));
        assertThat(Json.write(m)).isEqualTo("{\"b\":1,\"a\":[\"x\",2]}");
    }

    @Test
    void nestsMapsAndArrays() {
        assertThat(Json.write(Map.of("k", Map.of("n", new String[]{"t"}))))
                .isEqualTo("{\"k\":{\"n\":[\"t\"]}}");
    }

    @Test
    void writesPrimitiveArrays() {
        assertThat(Json.write(new int[]{1, 2})).isEqualTo("[1,2]");
        assertThat(Json.write(new long[]{3L})).isEqualTo("[3]");
        assertThat(Json.write(new double[]{1.5})).isEqualTo("[1.5]");
        assertThat(Json.write(new boolean[]{true, false})).isEqualTo("[true,false]");
        assertThat(Json.write(Map.of("k", new int[0]))).isEqualTo("{\"k\":[]}");
    }

    @Test
    void usesToStringForUnknownTypes() {
        assertThat(Json.write(Duration.ofSeconds(1))).isEqualTo("\"PT1S\"");
    }

    @Test
    void nonFiniteNumbersBecomeStrings() {
        assertThat(Json.write(Double.NaN)).isEqualTo("\"NaN\"");
    }
}
