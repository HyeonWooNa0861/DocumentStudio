package local.nahw.hwpxstudio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Json {
    private Json() {}

    static Object parse(String source) throws EngineException {
        Parser parser = new Parser(source);
        Object value = parser.value();
        parser.whitespace();
        if (!parser.end()) {
            throw new EngineException("JSON 문서 뒤에 불필요한 내용이 있습니다.");
        }
        return value;
    }

    static String stringify(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            string((String) value, out);
        } else if (value instanceof Boolean || value instanceof Number) {
            out.append(value.toString());
        } else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Object item : ((Map<?, ?>) value).entrySet()) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) item;
                if (!first) out.append(',');
                first = false;
                string(String.valueOf(entry.getKey()), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof Iterable) {
            out.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) out.append(',');
                first = false;
                write(item, out);
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("지원하지 않는 JSON 값: " + value.getClass());
        }
    }

    private static void string(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    private static final class Parser {
        private final String source;
        private int index;

        Parser(String source) {
            this.source = source;
        }

        boolean end() {
            return index == source.length();
        }

        void whitespace() {
            while (!end()) {
                char c = source.charAt(index);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') index++;
                else break;
            }
        }

        Object value() throws EngineException {
            whitespace();
            if (end()) throw error("JSON 값이 없습니다.");
            char c = source.charAt(index);
            if (c == '"') return string();
            if (c == '{') return object();
            if (c == '[') return array();
            if (source.startsWith("true", index)) { index += 4; return Boolean.TRUE; }
            if (source.startsWith("false", index)) { index += 5; return Boolean.FALSE; }
            if (source.startsWith("null", index)) { index += 4; return null; }
            if (c == '-' || Character.isDigit(c)) return number();
            throw error("올바른 JSON 값이 아닙니다.");
        }

        Map<String, Object> object() throws EngineException {
            LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
            index++;
            whitespace();
            if (take('}')) return result;
            while (true) {
                whitespace();
                if (end() || source.charAt(index) != '"') throw error("객체 키는 문자열이어야 합니다.");
                String key = string();
                if (result.containsKey(key)) throw error("중복된 객체 키가 있습니다: " + key);
                whitespace();
                if (!take(':')) throw error("객체 키 뒤에 ':'가 필요합니다.");
                result.put(key, value());
                whitespace();
                if (take('}')) return result;
                if (!take(',')) throw error("객체 항목 사이에 ','가 필요합니다.");
            }
        }

        List<Object> array() throws EngineException {
            ArrayList<Object> result = new ArrayList<Object>();
            index++;
            whitespace();
            if (take(']')) return result;
            while (true) {
                result.add(value());
                whitespace();
                if (take(']')) return result;
                if (!take(',')) throw error("배열 항목 사이에 ','가 필요합니다.");
            }
        }

        String string() throws EngineException {
            if (!take('"')) throw error("문자열이 필요합니다.");
            StringBuilder out = new StringBuilder();
            while (!end()) {
                char c = source.charAt(index++);
                if (c == '"') return out.toString();
                if (c == '\\') {
                    if (end()) throw error("문자열 이스케이프가 끝나지 않았습니다.");
                    char escaped = source.charAt(index++);
                    switch (escaped) {
                        case '"': out.append('"'); break;
                        case '\\': out.append('\\'); break;
                        case '/': out.append('/'); break;
                        case 'b': out.append('\b'); break;
                        case 'f': out.append('\f'); break;
                        case 'n': out.append('\n'); break;
                        case 'r': out.append('\r'); break;
                        case 't': out.append('\t'); break;
                        case 'u':
                            if (index + 4 > source.length()) throw error("유니코드 이스케이프가 잘못되었습니다.");
                            try {
                                out.append((char) Integer.parseInt(source.substring(index, index + 4), 16));
                            } catch (NumberFormatException e) {
                                throw error("유니코드 이스케이프가 잘못되었습니다.");
                            }
                            index += 4;
                            break;
                        default: throw error("지원하지 않는 문자열 이스케이프입니다.");
                    }
                } else {
                    if (c < 0x20) throw error("문자열에 제어 문자를 직접 넣을 수 없습니다.");
                    out.append(c);
                }
            }
            throw error("문자열이 끝나지 않았습니다.");
        }

        Number number() throws EngineException {
            int start = index;
            if (take('-') && end()) throw error("숫자가 잘못되었습니다.");
            if (take('0')) {
                // A zero cannot have additional integer digits.
            } else {
                if (end() || !Character.isDigit(source.charAt(index))) throw error("숫자가 잘못되었습니다.");
                while (!end() && Character.isDigit(source.charAt(index))) index++;
            }
            if (take('.')) {
                if (end() || !Character.isDigit(source.charAt(index))) throw error("숫자가 잘못되었습니다.");
                while (!end() && Character.isDigit(source.charAt(index))) index++;
            }
            if (!end() && (source.charAt(index) == 'e' || source.charAt(index) == 'E')) {
                index++;
                if (!end() && (source.charAt(index) == '+' || source.charAt(index) == '-')) index++;
                if (end() || !Character.isDigit(source.charAt(index))) throw error("숫자가 잘못되었습니다.");
                while (!end() && Character.isDigit(source.charAt(index))) index++;
            }
            String token = source.substring(start, index);
            try {
                if (token.indexOf('.') < 0 && token.indexOf('e') < 0 && token.indexOf('E') < 0) {
                    return Long.valueOf(token);
                }
                return Double.valueOf(token);
            } catch (NumberFormatException e) {
                throw error("숫자가 너무 크거나 잘못되었습니다.");
            }
        }

        boolean take(char expected) {
            if (!end() && source.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        EngineException error(String message) {
            return new EngineException(message + " (위치 " + index + ")");
        }
    }
}
