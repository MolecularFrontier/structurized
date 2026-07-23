package tech.molecules.structurized.gui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal reader for simple SMILES text files.
 *
 * <p>Accepted format is intentionally simple: every non-empty non-comment line contributes the
 * first token as the SMILES string. If a second token exists, it is treated as a molecule ID.</p>
 */
public final class SmilesInputReader {
    private SmilesInputReader() {}

    public record SmilesRecord(String smiles, String moleculeId) {}

    public static List<String> readSmilesFile(Path path) throws IOException {
        return parseSmilesLines(Files.readAllLines(path));
    }

    public static List<SmilesRecord> readSmilesRecords(Path path) throws IOException {
        return parseSmilesRecords(Files.readAllLines(path));
    }

    static List<String> parseSmilesLines(List<String> lines) {
        return parseSmilesRecords(lines).stream().map(SmilesRecord::smiles).toList();
    }

    static List<SmilesRecord> parseSmilesRecords(List<String> lines) {
        List<SmilesRecord> records = new ArrayList<>();
        boolean firstNonEmpty = true;
        for (String rawLine : lines) {
            if (rawLine == null) {
                continue;
            }
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            List<String> tokens = tokens(line);
            if (tokens.isEmpty()) {
                continue;
            }

            if (firstNonEmpty && tokens.getFirst().equalsIgnoreCase("smiles")) {
                firstNonEmpty = false;
                continue;
            }

            firstNonEmpty = false;
            String moleculeId = tokens.size() > 1 ? tokens.get(1) : null;
            records.add(new SmilesRecord(tokens.getFirst(), moleculeId));
        }
        return List.copyOf(records);
    }

    private static List<String> tokens(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (Character.isWhitespace(c) || c == ',' || c == ';') {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
