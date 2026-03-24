package ist.group29.depchain.common.keys;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses a simple addresses.config file into a Map of ID to Address.
 *
 * Expected file format (one line per entry):
 * 
 * node-0 0xabcdef...
 * client-0 0x123456...
 */
public class AddressConfigReader {

    /**
     * Parses the addresses config file and returns an ordered map of
     * ID -> 0x Address.
     */
    public static Map<String, String> parseAddresses(Path configPath) throws IOException {
        Map<String, String> nodes = new LinkedHashMap<>();

        try (BufferedReader reader = Files.newBufferedReader(configPath)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = line.trim();

                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                String[] parts = line.split("\\s+");
                if (parts.length != 2) {
                    throw new IOException(
                            "Invalid format at line " + lineNumber + ": expected '<id> <address>', got: " + line);
                }

                String id = parts[0];
                String address = parts[1];

                if (nodes.containsKey(id)) {
                    throw new IOException("Duplicate ID at line " + lineNumber + ": " + id);
                }

                nodes.put(id, address);
            }
        }

        if (nodes.isEmpty()) {
            throw new IOException("Config file is empty or contains no valid entries: " + configPath);
        }

        return nodes;
    }
}
