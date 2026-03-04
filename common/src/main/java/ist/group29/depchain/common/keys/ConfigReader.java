package ist.group29.depchain.common.keys;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import ist.group29.depchain.common.network.ProcessInfo;

/**
 * Parses a simple {@code hosts.config} file into {@link ProcessInfo} records.
 *
 * <p>Expected file format (one line per node):
 * <pre>
 *   node-0 127.0.0.1 8080
 *   node-1 127.0.0.1 8081
 *   node-2 127.0.0.1 8082
 *   node-3 127.0.0.1 8083
 * </pre>
 *
 * <p>Lines starting with {@code #} are treated as comments and ignored.
 * Blank lines are also skipped.
 */
public class ConfigReader {

    /**
     * Parses the hosts config file and returns an ordered map of
     * node ID → ProcessInfo (preserving insertion order).
     *
     * @param configPath path to the hosts.config file
     * @return ordered map of all declared nodes
     * @throws IOException if the file cannot be read or has an invalid format
     */
    public static Map<String, ProcessInfo> parseHosts(Path configPath) throws IOException {
        Map<String, ProcessInfo> nodes = new LinkedHashMap<>();

        try (BufferedReader reader = Files.newBufferedReader(configPath)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = line.trim();

                // Skip blank lines and comments
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                String[] parts = line.split("\\s+");
                if (parts.length != 3) {
                    throw new IOException(
                            "Invalid format at line " + lineNumber + ": expected '<id> <ip> <port>', got: " + line);
                }

                String id = parts[0];
                InetAddress address = InetAddress.getByName(parts[1]);
                int port = Integer.parseInt(parts[2]);

                if (nodes.containsKey(id)) {
                    throw new IOException("Duplicate node ID at line " + lineNumber + ": " + id);
                }

                nodes.put(id, new ProcessInfo(id, address, port));
            }
        }

        if (nodes.isEmpty()) {
            throw new IOException("Config file is empty or contains no valid entries: " + configPath);
        }

        return nodes;
    }
}
