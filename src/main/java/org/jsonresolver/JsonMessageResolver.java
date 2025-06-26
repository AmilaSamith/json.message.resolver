package org.jsonresolver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.layout.template.json.resolver.EventResolver;
import org.apache.logging.log4j.layout.template.json.resolver.TemplateResolverConfig;
import org.apache.logging.log4j.layout.template.json.util.JsonWriter;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Custom event resolver that converts log messages to proper JSON format.
 * Only processes messages from configured components for JSON parsing.
 */
@Plugin(name = "JsonMessage", category = "Resolver", elementType = "resolver")
public final class JsonMessageResolver implements EventResolver {

    private static final Logger LOGGER = LogManager.getLogger(JsonMessageResolver.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Gson GSON = new Gson();
    private static final JsonParser JSON_PARSER = new JsonParser();

    // Patterns for different integration artifacts (e.g : - apis, proxy-services)
    private static final Map<String, Pattern> EXTRACTION_PATTERNS = new LinkedHashMap<>();
    static {
        EXTRACTION_PATTERNS.put("api", Pattern.compile("\\{api:([^}]+)\\}"));
        EXTRACTION_PATTERNS.put("proxy", Pattern.compile("\\{proxy:([^}]+)\\}"));
        // Add more patterns as needed
    }

    // Configurations :
    // List of components the resolver should be applied
    private List<String> targetComponents;

    /**
     * Constructs a JsonMessageResolver with the given configuration.
     * 
     * @param config TemplateResolverConfig.
     */
    public JsonMessageResolver(TemplateResolverConfig config) {
        this.targetComponents = config != null ? config.getList("components", String.class)
                : Collections.emptyList();

        if (!targetComponents.isEmpty()) {
            LOGGER.info("JsonMessageResolver initialized with target components: {}", targetComponents);
        } else {
            LOGGER.info("No target components were configured for JsonMessageResolver");
        }
    }

    /**
     * Resolves the log event message, converting it to JSON if applicable, and
     * writes it using the provided JsonWriter.
     * 
     * @param logEvent   the log event to resolve
     * @param jsonWriter the writer to output the resolved JSON
     */
    @Override
    public void resolve(LogEvent logEvent, JsonWriter jsonWriter) {
        if (logEvent == null || logEvent.getMessage() == null || jsonWriter == null) {
            if (jsonWriter != null) {
                jsonWriter.writeString("");
            }
            return;
        }

        String originalMessage = logEvent.getMessage().getFormattedMessage();
        if (originalMessage == null || originalMessage.trim().isEmpty()) {
            jsonWriter.writeString("");
            return;
        }

        boolean shouldProcessAsJson = isTargetComponent(logEvent);

        // For non-target components, pass through as-is
        if (!shouldProcessAsJson) {
            jsonWriter.writeString(originalMessage);
            return;
        }

        // For target components, always try to convert to proper JSON structure
        String normalized = normalizeQuotes(originalMessage);

        try {
            // First check if it's already valid JSON
            JsonNode existingJson = tryParseJson(normalized);
            if (existingJson != null) {
                // Process any nested JSON strings and write as JSON object
                JsonNode processedJson = processExistingJson(existingJson, true);
                writeJsonObject(jsonWriter, processedJson);
                return;
            }

            // Parse the message format into JSON
            Map<String, Object> jsonMap = parseMessage(normalized, true);
            Map<String, Object> cleanedMap = deepCleanParsedMap(jsonMap, true);

            // Write as JSON object, not string
            JsonNode result = OBJECT_MAPPER.valueToTree(cleanedMap);
            writeJsonObject(jsonWriter, result);

        } catch (Exception e) {
            LOGGER.warn("Failed to process message from component '{}': '{}', error: {}",
                    logEvent.getLoggerName(), originalMessage, e.getMessage());
            jsonWriter.writeString(originalMessage);
        }
    }

    /**
     * Writes a JsonNode as a JSON object using the provided JsonWriter.
     * Falls back to writing as a string if conversion fails.
     * 
     * @param jsonWriter the writer to output the JSON
     * @param jsonNode   the JsonNode to write
     */
    private void writeJsonObject(JsonWriter jsonWriter, JsonNode jsonNode) {
        try {
            // Convert JsonNode to Map/List structure for proper writing
            Object value = convertJsonNodeToObject(jsonNode);
            jsonWriter.writeValue(value);
        } catch (Exception e) {
            LOGGER.warn("Failed to write JSON object, falling back to string: {}", e.getMessage());
            jsonWriter.writeString(jsonNode.toString());
        }
    }

    /**
     * Checks if the log event's logger name matches any of the configured target
     * components.
     * 
     * @param logEvent the log event to check
     * @return true if the logger is a target component, false otherwise
     */
    private boolean isTargetComponent(LogEvent logEvent) {
        if (logEvent.getLoggerName() == null) {
            return false;
        }

        String loggerName = logEvent.getLoggerName();

        // Check if the logger name matches any of the target components
        boolean matches = targetComponents.contains(loggerName) ||
                targetComponents.stream()
                        .anyMatch(component -> loggerName.startsWith(component) || loggerName.endsWith(component));

        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Component check - Logger: '{}', Target components: {}, Matches: {}",
                    loggerName, targetComponents, matches);
        }

        return matches;
    }

    /**
     * Attempts to parse the given message string as JSON.
     * 
     * @param message the message to parse
     * @return a JsonNode if parsing succeeds, null otherwise
     */
    private JsonNode tryParseJson(String message) {
        if (message == null || message.trim().isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(message);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Normalizes various quote characters in the input string to standard quotes.
     * 
     * @param value the string to normalize
     * @return the normalized string
     */
    private String normalizeQuotes(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder result = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"':
                case '\u201C':
                case '\u201D':
                    result.append('"');
                    break;
                case '\'':
                case '\u2018':
                case '\u2019':
                    result.append('\'');
                    break;
                default:
                    result.append(c);
            }
        }
        return result.toString();
    }

    /**
     * Recursively processes a JsonNode, parsing nested JSON strings if required.
     * 
     * @param node              the JsonNode to process
     * @param shouldProcessJson whether to attempt parsing nested JSON strings
     * @return a processed JsonNode with nested JSON parsed
     */
    private JsonNode processExistingJson(JsonNode node, boolean shouldProcessJson) {
        if (node.isObject()) {
            Map<String, Object> result = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey();
                JsonNode value = entry.getValue();

                if (value.isTextual()) {
                    String textValue = value.textValue();
                    // Only try to parse nested JSON for target components
                    if (shouldProcessJson) {
                        Object parsedValue = tryParseJsonString(textValue, true);
                        result.put(key, parsedValue);
                    } else {
                        result.put(key, textValue);
                    }
                } else {
                    result.put(key, convertJsonNodeToObject(processExistingJson(value, shouldProcessJson)));
                }
            }
            return OBJECT_MAPPER.valueToTree(result);
        } else if (node.isArray()) {
            List<Object> result = new ArrayList<>();
            for (JsonNode element : node) {
                result.add(convertJsonNodeToObject(processExistingJson(element, shouldProcessJson)));
            }
            return OBJECT_MAPPER.valueToTree(result);
        }
        return node;
    }

    /**
     * Attempts to parse a string as JSON or a simple value, depending on the
     * content and flag.
     * 
     * @param value             the string to parse
     * @param shouldProcessJson whether to attempt JSON parsing
     * @return the parsed object or the original string
     */
    private Object tryParseJsonString(String value, boolean shouldProcessJson) {
        if (value == null || value.trim().isEmpty()) {
            return value;
        }

        String trimmed = normalizeQuotes(value.trim());

        // Only attempt JSON parsing for target components
        if (shouldProcessJson && ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
                (trimmed.startsWith("[") && trimmed.endsWith("]")))) {

            // Try Gson first for better JSON parsing
            try {
                JsonElement jsonElement = JSON_PARSER.parse(trimmed);
                return GSON.fromJson(jsonElement, Object.class);
            } catch (JsonSyntaxException e) {
                // Fall back to Jackson if Gson fails
                JsonNode parsed = tryParseJson(trimmed);
                if (parsed != null) {
                    return convertJsonNodeToObject(parsed);
                }
            }
        }

        return parseSimpleValue(trimmed);
    }

    /**
     * Parses a string as a simple value (boolean, number, or unquoted string).
     * 
     * @param value the string to parse
     * @return the parsed value
     */
    private Object parseSimpleValue(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.parseBoolean(value);
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            // Not a number
        }
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * Parses a log message into a map of key-value pairs and extracted patterns.
     * 
     * @param message           the message to parse
     * @param shouldProcessJson whether to attempt JSON parsing for values
     * @return a map representing the parsed message
     */
    private Map<String, Object> parseMessage(String message, boolean shouldProcessJson) {
        Map<String, Object> result = new LinkedHashMap<>();
        String cleanedMessage = message;

        // Extract all configured patterns
        for (Map.Entry<String, Pattern> patternEntry : EXTRACTION_PATTERNS.entrySet()) {
            String patternName = patternEntry.getKey();
            Pattern pattern = patternEntry.getValue();

            Matcher matcher = pattern.matcher(cleanedMessage);
            List<String> matches = new ArrayList<>();
            while (matcher.find()) {
                matches.add(matcher.group(1));
                cleanedMessage = cleanedMessage.replace(matcher.group(0), "");
            }
            if (!matches.isEmpty()) {
                result.put(patternName, matches.size() == 1 ? matches.get(0) : matches);
            }
        }

        // Parse key-value pairs (rest of the method remains the same)
        List<String> segments = splitMessage(cleanedMessage);
        boolean hasKeyValuePairs = false;
        for (String segment : segments) {
            if (parseKeyValuePair(segment.trim(), result, shouldProcessJson)) {
                hasKeyValuePairs = true;
            }
        }

        // Fallback to plain text if no key-value pairs or patterns found
        if (!hasKeyValuePairs && !cleanedMessage.trim().isEmpty()) {
            result.put("text", cleanedMessage.trim());
        }
        return result;
    }

    /**
     * Splits a message into segments, respecting nested structures and quoted
     * strings.
     * 
     * @param message the message to split
     * @return a list of segments
     */
    private List<String> splitMessage(String message) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inQuotes = false;
        boolean escaped = false;

        for (char c : message.toCharArray()) {
            if (c == '\\' && !escaped) {
                escaped = true;
                current.append(c);
                continue;
            }
            if (c == '"' && !escaped) {
                inQuotes = !inQuotes;
            }
            if (!inQuotes) {
                if (c == '{' || c == '[')
                    depth++;
                else if (c == '}' || c == ']')
                    depth--;
                else if (c == ',' && depth == 0) {
                    if (current.length() > 0) {
                        segments.add(current.toString());
                        current = new StringBuilder();
                        escaped = false;
                        continue;
                    }
                }
            }
            current.append(c);
            escaped = false;
        }
        if (current.length() > 0) {
            segments.add(current.toString());
        }
        return segments;
    }

    /**
     * Parses a segment as a key-value pair and adds it to the result map.
     * 
     * @param segment           the segment to parse
     * @param result            the map to add the key-value pair to
     * @param shouldProcessJson whether to attempt JSON parsing for the value
     * @return true if a key-value pair was parsed, false otherwise
     */
    private boolean parseKeyValuePair(String segment, Map<String, Object> result, boolean shouldProcessJson) {
        if (segment.isEmpty()) {
            return false;
        }

        String key = null;
        String value = null;

        // Find the first colon or equals that's not inside quotes
        int separatorIndex = -1;
        boolean inQuotes = false;
        boolean escaped = false;

        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);

            if (c == '\\' && !escaped) {
                escaped = true;
                continue;
            }

            if (c == '"' && !escaped) {
                inQuotes = !inQuotes;
            } else if (!inQuotes && (c == ':' || c == '=') && !escaped) {
                separatorIndex = i;
                break;
            }

            escaped = false;
        }

        if (separatorIndex > 0 && separatorIndex < segment.length() - 1) {
            key = segment.substring(0, separatorIndex).trim();
            value = segment.substring(separatorIndex + 1).trim();

            if (!key.isEmpty()) {
                result.put(key, parseValue(value, shouldProcessJson));
                return true;
            }
        }

        return false;
    }

    /**
     * Parses a value string, attempting JSON parsing if applicable.
     * 
     * @param value             the value string to parse
     * @param shouldProcessJson whether to attempt JSON parsing
     * @return the parsed value
     */
    private Object parseValue(String value, boolean shouldProcessJson) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        // Only attempt JSON parsing for target components
        if (shouldProcessJson && ((value.startsWith("{") && value.endsWith("}")) ||
                (value.startsWith("[") && value.endsWith("]")))) {

            // Try Gson first
            try {
                JsonElement jsonElement = JSON_PARSER.parse(normalizeQuotes(value));
                return GSON.fromJson(jsonElement, Object.class);
            } catch (JsonSyntaxException e) {
                // Fall back to Jackson
                JsonNode node = tryParseJson(normalizeQuotes(value));
                if (node != null) {
                    return convertJsonNodeToObject(node);
                }
            }
        }
        return parseSimpleValue(value);
    }

    /**
     * Converts a JsonNode to a corresponding Java object (Map, List, primitive, or
     * String).
     * 
     * @param node the JsonNode to convert
     * @return the converted Java object
     */
    private Object convertJsonNodeToObject(JsonNode node) {
        if (node.isNull()) {
            return null;
        } else if (node.isBoolean()) {
            return node.booleanValue();
        } else if (node.isNumber()) {
            if (node.isInt()) {
                return node.intValue();
            } else if (node.isLong()) {
                return node.longValue();
            } else {
                return node.doubleValue();
            }
        } else if (node.isTextual()) {
            return node.textValue();
        } else if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode element : node) {
                list.add(convertJsonNodeToObject(element));
            }
            return list;
        } else if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                map.put(entry.getKey(), convertJsonNodeToObject(entry.getValue()));
            }
            return map;
        }
        return node.toString();
    }

    /**
     * Recursively cleans a parsed map, parsing nested JSON strings if required.
     * 
     * @param input             the map to clean
     * @param shouldProcessJson whether to attempt JSON parsing for string values
     * @return the cleaned map
     */
    private Map<String, Object> deepCleanParsedMap(Map<String, Object> input, boolean shouldProcessJson) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) {
                result.put(entry.getKey(), tryParseJsonString((String) value, shouldProcessJson));
            } else if (value instanceof Map) {
                result.put(entry.getKey(), deepCleanParsedMap((Map<String, Object>) value, shouldProcessJson));
            } else {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    /**
     * Returns the name of this resolver.
     * 
     * @return the resolver name
     */
    static String getName() {
        return "JsonMessage";
    }
}
