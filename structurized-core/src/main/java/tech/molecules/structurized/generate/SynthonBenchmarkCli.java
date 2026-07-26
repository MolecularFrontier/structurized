package tech.molecules.structurized.generate;

import com.actelion.research.chem.Canonizer;
import com.actelion.research.chem.IDCodeParser;
import com.actelion.research.chem.IsomericSmilesCreator;
import com.actelion.research.chem.MolecularFormula;
import com.actelion.research.chem.Molecule;
import com.actelion.research.chem.SmilesParser;
import com.actelion.research.chem.StereoMolecule;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Mines connector-bearing synthons from normalized molecule TSVs and creates deterministic
 * synthetic benchmark datasets from the mined vocabulary.
 */
public final class SynthonBenchmarkCli {
    private SynthonBenchmarkCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            printUsage();
            return;
        }

        String command = args[0];
        Arguments parsed = Arguments.parse(Arrays.copyOfRange(args, 1, args.length));
        switch (command) {
            case "mine-synthons" -> runMine(parsed);
            case "make-benchmark" -> runMakeBenchmark(parsed);
            case "perturb-benchmark" -> runPerturbBenchmark(parsed);
            default -> {
                System.err.println("Unknown command: " + command);
                printUsage();
                System.exit(2);
            }
        }
    }

    private static void printUsage() {
        System.out.println("""
                Usage:
                  structurized-generate mine-synthons --input chembl.tsv[.gz] --output-dir DIR [options]
                  structurized-generate make-benchmark --mining-dir DIR --output-dir DIR [options]
                  structurized-generate perturb-benchmark --input-dir DIR --output-dir DIR [options]

                mine-synthons options:
                  --max-molecules N              default: unlimited
                  --max-cut-instances N          default: 1000000
                  --max-cuts-per-molecule N      default: 200
                  --max-cuts 1|2                 default: 2
                  --max-terminal-heavy-atoms N   default: 14
                  --max-middle-heavy-atoms N     default: 20
                  --max-connector-distance N     default: 10

                make-benchmark options:
                  --max-products N               default: 10000
                  --seed N                       default: 1
                  --mode one-position|two-position|mixed  default: mixed
                  --pool-sample-size N           default: 64
                  --matrix-size N                default: 8

                perturb-benchmark options:
                  --drop-rate X                  default: 0.25
                  --distractor-rate X            default: 0.10
                  --merge-rate X                 default: 0.05
                  --seed N                       default: 1
                """);
    }

    private static void runMine(Arguments args) throws IOException {
        Path input = args.requiredPath("input");
        Path outputDir = args.requiredPath("output-dir");
        Files.createDirectories(outputDir);

        MiningConfig config = new MiningConfig(
                args.longValue("max-molecules", Long.MAX_VALUE),
                args.longValue("max-cut-instances", 1_000_000L),
                args.intValue("max-cuts-per-molecule", 200),
                args.intValue("max-cuts", 2),
                args.intValue("max-terminal-heavy-atoms", 14),
                args.intValue("max-middle-heavy-atoms", 20),
                args.intValue("max-connector-distance", 10)
        );
        SynthonMiner miner = new SynthonMiner(config);
        miner.mine(input, outputDir);
    }

    private static void runMakeBenchmark(Arguments args) throws IOException {
        Path miningDir = args.requiredPath("mining-dir");
        Path outputDir = args.requiredPath("output-dir");
        Files.createDirectories(outputDir);

        BenchmarkConfig config = new BenchmarkConfig(
                args.longValue("max-products", 10_000L),
                args.longValue("seed", 1L),
                args.value("mode", "mixed"),
                args.intValue("pool-sample-size", 64),
                args.intValue("matrix-size", 8),
                args.intValue("min-product-heavy-atoms", 8),
                args.intValue("max-product-heavy-atoms", 60),
                args.doubleValue("min-product-mw", 100.0),
                args.doubleValue("max-product-mw", 700.0)
        );
        BenchmarkGenerator generator = new BenchmarkGenerator(config);
        generator.generate(miningDir, outputDir);
    }

    private static void runPerturbBenchmark(Arguments args) throws IOException {
        Path inputDir = args.requiredPath("input-dir");
        Path outputDir = args.requiredPath("output-dir");
        Files.createDirectories(outputDir);

        PerturbConfig config = new PerturbConfig(
                args.doubleValue("drop-rate", 0.25),
                args.doubleValue("distractor-rate", 0.10),
                args.doubleValue("merge-rate", 0.05),
                args.longValue("seed", 1L)
        );
        BenchmarkPerturber perturber = new BenchmarkPerturber(config);
        perturber.perturb(inputDir, outputDir);
    }

    private record Arguments(Map<String, String> values) {
        static Arguments parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < args.length; i++) {
                String key = args[i];
                if (!key.startsWith("--")) {
                    throw new IllegalArgumentException("Expected option, got: " + key);
                }
                String name = key.substring(2);
                if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    values.put(name, "true");
                } else {
                    values.put(name, args[++i]);
                }
            }
            return new Arguments(values);
        }

        String value(String key, String defaultValue) {
            return values.getOrDefault(key, defaultValue);
        }

        Path requiredPath(String key) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing required option --" + key);
            }
            return Path.of(value);
        }

        int intValue(String key, int defaultValue) {
            return Integer.parseInt(value(key, Integer.toString(defaultValue)));
        }

        long longValue(String key, long defaultValue) {
            return Long.parseLong(value(key, Long.toString(defaultValue)));
        }

        double doubleValue(String key, double defaultValue) {
            return Double.parseDouble(value(key, Double.toString(defaultValue)));
        }
    }
}

record MiningConfig(
        long maxMolecules,
        long maxCutInstances,
        int maxCutsPerMolecule,
        int maxCuts,
        int maxTerminalHeavyAtoms,
        int maxMiddleHeavyAtoms,
        int maxConnectorDistance
) {
    MiningConfig {
        if (maxCuts < 1 || maxCuts > 2) {
            throw new IllegalArgumentException("maxCuts must be 1 or 2");
        }
        if (maxCutsPerMolecule < 1 || maxCutInstances < 1 || maxMolecules < 1) {
            throw new IllegalArgumentException("count limits must be positive");
        }
    }
}

record BenchmarkConfig(
        long maxProducts,
        long seed,
        String mode,
        int poolSampleSize,
        int matrixSize,
        int minProductHeavyAtoms,
        int maxProductHeavyAtoms,
        double minProductMw,
        double maxProductMw
) {
    BenchmarkConfig {
        if (!Set.of("one-position", "two-position", "mixed").contains(mode)) {
            throw new IllegalArgumentException("mode must be one-position, two-position, or mixed");
        }
    }
}

record PerturbConfig(double dropRate, double distractorRate, double mergeRate, long seed) {
    PerturbConfig {
        if (dropRate < 0.0 || dropRate >= 1.0 || distractorRate < 0.0 || mergeRate < 0.0) {
            throw new IllegalArgumentException("invalid perturbation rates");
        }
    }
}

final class SynthonMiner {
    private final MiningConfig config;

    SynthonMiner(MiningConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    void mine(Path input, Path outputDir) throws IOException {
        Map<String, MutableSynthon> synthons = new LinkedHashMap<>();
        Map<String, MutableReplacementClass> replacementClasses = new LinkedHashMap<>();
        MiningStats stats = new MiningStats();

        try (BufferedReader reader = Tsv.openReader(input);
             BufferedWriter sourceWriter = Tsv.openWriter(outputDir.resolve("source_molecules.tsv"));
             BufferedWriter cutWriter = Tsv.openWriter(outputDir.resolve("cut_instances.tsv"))) {

            sourceWriter.write("molecule_id\tsource_id\tstandardized_idcode\tcanonical_smiles\theavy_atoms\tmolecular_weight\tformal_charge\n");
            cutWriter.write("cut_instance_id\tsource_molecule_id\tcut_count\tcomponent_synthon_ids\tcomponent_connector_counts\tcomponent_attachment_signatures\treconstruction_idcode\n");

            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IOException("Input TSV is empty: " + input);
            }
            Tsv.Header header = Tsv.Header.parse(headerLine);
            String line;
            long moleculeIndex = 0;
            long acceptedCuts = 0;
            while ((line = reader.readLine()) != null) {
                if (++moleculeIndex > config.maxMolecules() || acceptedCuts >= config.maxCutInstances()) {
                    break;
                }
                SourceMolecule source;
                try {
                    source = SourceMolecule.fromTsv(header, line);
                } catch (Exception e) {
                    stats.parseFailures++;
                    continue;
                }
                stats.scannedMolecules++;
                sourceWriter.write(String.join("\t",
                        Tsv.clean(source.moleculeId()),
                        Tsv.clean(source.sourceId()),
                        Tsv.clean(source.idcode()),
                        Tsv.clean(source.smiles()),
                        Integer.toString(source.heavyAtoms()),
                        Tsv.formatDouble(source.molecularWeight()),
                        Integer.toString(source.formalCharge())));
                sourceWriter.write('\n');

                List<CutResult> cuts;
                try {
                    cuts = CutEnumerator.enumerate(source, config);
                } catch (Exception e) {
                    stats.enumerationFailures++;
                    continue;
                }
                int cutsForMolecule = 0;
                for (CutResult cut : cuts) {
                    if (acceptedCuts >= config.maxCutInstances() || cutsForMolecule >= config.maxCutsPerMolecule()) {
                        break;
                    }
                    if (!RoundTripValidator.matches(source.idcode(), cut.components())) {
                        stats.roundTripFailures++;
                        continue;
                    }

                    List<String> synthonIds = new ArrayList<>();
                    List<String> connectorCounts = new ArrayList<>();
                    List<String> attachmentSignatures = new ArrayList<>();
                    for (SynthonComponent component : cut.components()) {
                        String synthonId = SynthonIds.synthonId(component.idcode());
                        synthonIds.add(synthonId);
                        connectorCounts.add(Integer.toString(component.connectorCount()));
                        attachmentSignatures.add(component.attachmentSignature());
                        MutableSynthon synthon = synthons.computeIfAbsent(synthonId, id -> new MutableSynthon(id, component));
                        synthon.addOccurrence(source.sourceId(), component.attachmentSignature());
                    }

                    String cutInstanceId = SynthonIds.cutInstanceId(source.moleculeId(), cut.cutCount(), synthonIds, attachmentSignatures);
                    cutWriter.write(String.join("\t",
                            cutInstanceId,
                            Tsv.clean(source.moleculeId()),
                            Integer.toString(cut.cutCount()),
                            String.join("|", synthonIds),
                            String.join("|", connectorCounts),
                            Tsv.joinEncoded(attachmentSignatures),
                            Tsv.clean(source.idcode())));
                    cutWriter.write('\n');

                    for (int position = 0; position < synthonIds.size(); position++) {
                        List<String> fixed = new ArrayList<>(synthonIds);
                        String value = fixed.remove(position);
                        fixed.sort(String::compareTo);
                        String signature = attachmentSignatures.get(position);
                        int variablePosition = position;
                        List<String> fixedForClass = List.copyOf(fixed);
                        String envId = SynthonIds.environmentId(cut.cutCount(), fixedForClass, variablePosition, signature);
                        MutableReplacementClass cls = replacementClasses.computeIfAbsent(
                                envId,
                                id -> new MutableReplacementClass(id, cut.cutCount(), fixedForClass, variablePosition, signature));
                        cls.add(value);
                    }

                    acceptedCuts++;
                    cutsForMolecule++;
                }
            }
        }

        writeSynthons(outputDir.resolve("synthons.tsv"), synthons);
        writeReplacementClasses(outputDir.resolve("replacement_classes.tsv"), replacementClasses);
        stats.write(outputDir.resolve("mining_report.txt"), synthons.size(), replacementClasses.size());
    }

    private static void writeSynthons(Path output, Map<String, MutableSynthon> synthons) throws IOException {
        try (BufferedWriter writer = Tsv.openWriter(output)) {
            writer.write("synthon_id\tidcode\tconnector_count\theavy_atoms\tmolecular_weight\toccurrence_count\tattachment_signatures\texample_source_ids\n");
            List<MutableSynthon> ordered = new ArrayList<>(synthons.values());
            ordered.sort(Comparator.comparing(MutableSynthon::id));
            for (MutableSynthon synthon : ordered) {
                writer.write(String.join("\t",
                        synthon.id(),
                        Tsv.clean(synthon.idcode()),
                        Integer.toString(synthon.connectorCount()),
                        Integer.toString(synthon.heavyAtoms()),
                        Tsv.formatDouble(synthon.molecularWeight()),
                        Integer.toString(synthon.occurrenceCount()),
                        Tsv.joinEncoded(synthon.attachmentSignatures()),
                        String.join("|", synthon.exampleSourceIds())));
                writer.write('\n');
            }
        }
    }

    private static void writeReplacementClasses(Path output, Map<String, MutableReplacementClass> classes) throws IOException {
        try (BufferedWriter writer = Tsv.openWriter(output)) {
            writer.write("environment_id\tcut_count\tfixed_component_ids\tvariable_position\tattachment_signature\tpossible_synthon_ids\tobservation_count\n");
            List<MutableReplacementClass> ordered = new ArrayList<>(classes.values());
            ordered.sort(Comparator.comparing(MutableReplacementClass::environmentId));
            for (MutableReplacementClass cls : ordered) {
                writer.write(String.join("\t",
                        cls.environmentId(),
                        Integer.toString(cls.cutCount()),
                        String.join("|", cls.fixedComponentIds()),
                        Integer.toString(cls.variablePosition()),
                        cls.attachmentSignature(),
                        String.join("|", cls.possibleSynthonIds()),
                        Integer.toString(cls.observationCount())));
                writer.write('\n');
            }
        }
    }
}

final class BenchmarkGenerator {
    private final BenchmarkConfig config;

    BenchmarkGenerator(BenchmarkConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    void generate(Path miningDir, Path outputDir) throws IOException {
        Map<String, SynthonTableRecord> synthons = readSynthons(miningDir.resolve("synthons.tsv"));
        Map<String, List<SynthonTableRecord>> poolsBySignature = buildPoolsBySignature(synthons.values());
        Set<String> emittedProducts = new HashSet<>();

        try (BufferedReader cuts = Tsv.openReader(miningDir.resolve("cut_instances.tsv"));
             BufferedWriter molecules = Tsv.openWriter(outputDir.resolve("generated_molecules.tsv"));
             BufferedWriter truth = Tsv.openWriter(outputDir.resolve("generation_truth.tsv"))) {

            molecules.write("generated_id\tseries_id\tgeneration_mode\tproduct_smiles\tproduct_idcode\theavy_atoms\tmolecular_weight\tformal_charge\n");
            truth.write("generated_id\tseries_id\tgeneration_mode\tseed_cut_instance_id\tfixed_synthon_ids\tvariable_synthon_ids\tsamplings_seed\n");

            Tsv.Header header = Tsv.Header.parse(cuts.readLine());
            String line;
            long productIndex = 0;
            while ((line = cuts.readLine()) != null && productIndex < config.maxProducts()) {
                CutInstanceRow cut = CutInstanceRow.fromTsv(header, line);
                if (("one-position".equals(config.mode()) || "mixed".equals(config.mode())) && cut.cutCount() == 1) {
                    productIndex = generateOnePosition(cut, synthons, poolsBySignature, emittedProducts, productIndex, molecules, truth);
                }
                if (productIndex >= config.maxProducts()) {
                    break;
                }
                if (("two-position".equals(config.mode()) || "mixed".equals(config.mode())) && cut.cutCount() == 2) {
                    productIndex = generateTwoPosition(cut, synthons, poolsBySignature, emittedProducts, productIndex, molecules, truth);
                }
            }
        }
    }

    private long generateOnePosition(
            CutInstanceRow cut,
            Map<String, SynthonTableRecord> synthons,
            Map<String, List<SynthonTableRecord>> poolsBySignature,
            Set<String> emittedProducts,
            long productIndex,
            BufferedWriter molecules,
            BufferedWriter truth
    ) throws IOException {
        if (cut.componentIds().size() != 2) {
            return productIndex;
        }
        int variablePosition = variableTerminalPosition(cut, synthons);
        int fixedPosition = 1 - variablePosition;
        SynthonTableRecord fixed = synthons.get(cut.componentIds().get(fixedPosition));
        SynthonTableRecord variable = synthons.get(cut.componentIds().get(variablePosition));
        if (fixed == null || variable == null || variable.connectorCount() != 1) {
            return productIndex;
        }

        String signature = cut.attachmentSignatures().get(variablePosition);
        List<SynthonTableRecord> pool = sampledPool(
                poolsBySignature.getOrDefault(signature, List.of()),
                candidate -> candidate.connectorCount() == 1
                        && Math.abs(candidate.heavyAtoms() - variable.heavyAtoms()) <= 5,
                config.seed() ^ cut.cutInstanceId().hashCode(),
                config.poolSampleSize());

        String seriesId = "series_" + Hashing.shortHash(cut.cutInstanceId() + "|one");
        for (SynthonTableRecord candidate : pool) {
            if (productIndex >= config.maxProducts()) {
                break;
            }
            Product product = tryAssemble(List.of(fixed, candidate));
            if (product == null || !passesProductFilters(product) || !emittedProducts.add(product.idcode())) {
                continue;
            }
            String generatedId = "gen_" + (++productIndex);
            writeProduct(molecules, generatedId, seriesId, "one_position", product);
            truth.write(String.join("\t",
                    generatedId,
                    seriesId,
                    "one_position",
                    cut.cutInstanceId(),
                    fixed.synthonId(),
                    candidate.synthonId(),
                    Long.toString(config.seed())));
            truth.write('\n');
        }
        return productIndex;
    }

    private long generateTwoPosition(
            CutInstanceRow cut,
            Map<String, SynthonTableRecord> synthons,
            Map<String, List<SynthonTableRecord>> poolsBySignature,
            Set<String> emittedProducts,
            long productIndex,
            BufferedWriter molecules,
            BufferedWriter truth
    ) throws IOException {
        if (cut.componentIds().size() != 3) {
            return productIndex;
        }
        int fixedPosition = -1;
        List<Integer> terminals = new ArrayList<>();
        for (int i = 0; i < cut.componentIds().size(); i++) {
            SynthonTableRecord synthon = synthons.get(cut.componentIds().get(i));
            if (synthon == null) {
                return productIndex;
            }
            if (synthon.connectorCount() == 2) {
                fixedPosition = i;
            } else if (synthon.connectorCount() == 1) {
                terminals.add(i);
            }
        }
        if (fixedPosition < 0 || terminals.size() != 2) {
            return productIndex;
        }

        SynthonTableRecord fixed = synthons.get(cut.componentIds().get(fixedPosition));
        List<SynthonTableRecord> pool0 = sampledPool(
                poolsBySignature.getOrDefault(cut.attachmentSignatures().get(terminals.get(0)), List.of()),
                candidate -> candidate.connectorCount() == 1,
                config.seed() ^ (cut.cutInstanceId() + "|0").hashCode(),
                config.matrixSize());
        List<SynthonTableRecord> pool1 = sampledPool(
                poolsBySignature.getOrDefault(cut.attachmentSignatures().get(terminals.get(1)), List.of()),
                candidate -> candidate.connectorCount() == 1,
                config.seed() ^ (cut.cutInstanceId() + "|1").hashCode(),
                config.matrixSize());

        String seriesId = "series_" + Hashing.shortHash(cut.cutInstanceId() + "|two");
        for (SynthonTableRecord a : pool0) {
            for (SynthonTableRecord b : pool1) {
                if (productIndex >= config.maxProducts()) {
                    return productIndex;
                }
                Product product = tryAssemble(List.of(fixed, a, b));
                if (product == null || !passesProductFilters(product) || !emittedProducts.add(product.idcode())) {
                    continue;
                }
                String generatedId = "gen_" + (++productIndex);
                writeProduct(molecules, generatedId, seriesId, "two_position", product);
                truth.write(String.join("\t",
                        generatedId,
                        seriesId,
                        "two_position",
                        cut.cutInstanceId(),
                        fixed.synthonId(),
                        a.synthonId() + "|" + b.synthonId(),
                        Long.toString(config.seed())));
                truth.write('\n');
            }
        }
        return productIndex;
    }

    private static int variableTerminalPosition(CutInstanceRow cut, Map<String, SynthonTableRecord> synthons) {
        SynthonTableRecord a = synthons.get(cut.componentIds().get(0));
        SynthonTableRecord b = synthons.get(cut.componentIds().get(1));
        if (a == null || b == null) {
            return 0;
        }
        return a.heavyAtoms() <= b.heavyAtoms() ? 0 : 1;
    }

    private static Map<String, SynthonTableRecord> readSynthons(Path input) throws IOException {
        Map<String, SynthonTableRecord> synthons = new LinkedHashMap<>();
        try (BufferedReader reader = Tsv.openReader(input)) {
            Tsv.Header header = Tsv.Header.parse(reader.readLine());
            String line;
            while ((line = reader.readLine()) != null) {
                SynthonTableRecord record = SynthonTableRecord.fromTsv(header, line);
                synthons.put(record.synthonId(), record);
            }
        }
        return synthons;
    }

    private static Map<String, List<SynthonTableRecord>> buildPoolsBySignature(Iterable<SynthonTableRecord> synthons) {
        Map<String, List<SynthonTableRecord>> pools = new HashMap<>();
        for (SynthonTableRecord synthon : synthons) {
            for (String signature : synthon.attachmentSignatures()) {
                pools.computeIfAbsent(signature, key -> new ArrayList<>()).add(synthon);
            }
        }
        for (List<SynthonTableRecord> pool : pools.values()) {
            pool.sort(Comparator.comparing(SynthonTableRecord::synthonId));
        }
        return pools;
    }

    private static List<SynthonTableRecord> sampledPool(
            List<SynthonTableRecord> pool,
            Predicate<SynthonTableRecord> predicate,
            long seed,
            int limit
    ) {
        List<SynthonTableRecord> filtered = pool.stream().filter(predicate).toList();
        return filtered.stream()
                .sorted(Comparator.comparing(record -> Hashing.shortHash(record.synthonId() + "|" + seed)))
                .limit(limit)
                .toList();
    }

    private Product tryAssemble(List<SynthonTableRecord> parts) {
        try {
            StereoMolecule assembled = ConnectorAssembler.assemble(parts.stream().map(SynthonTableRecord::idcode).toList());
            assembled.ensureHelperArrays(Molecule.cHelperCIP);
            String idcode = new Canonizer(assembled).getIDCode();
            String smiles = IsomericSmilesCreator.createSmiles(assembled);
            int heavyAtoms = Chemistry.heavyAtomCount(assembled);
            double mw = Chemistry.molecularWeight(assembled);
            int charge = Chemistry.formalCharge(assembled);
            return new Product(idcode, smiles, heavyAtoms, mw, charge);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean passesProductFilters(Product product) {
        return product.heavyAtoms() >= config.minProductHeavyAtoms()
                && product.heavyAtoms() <= config.maxProductHeavyAtoms()
                && product.molecularWeight() >= config.minProductMw()
                && product.molecularWeight() <= config.maxProductMw()
                && product.formalCharge() >= -2
                && product.formalCharge() <= 2;
    }

    private static void writeProduct(BufferedWriter writer, String generatedId, String seriesId, String mode, Product product)
            throws IOException {
        writer.write(String.join("\t",
                generatedId,
                seriesId,
                mode,
                Tsv.clean(product.smiles()),
                Tsv.clean(product.idcode()),
                Integer.toString(product.heavyAtoms()),
                Tsv.formatDouble(product.molecularWeight()),
                Integer.toString(product.formalCharge())));
        writer.write('\n');
    }
}

final class BenchmarkPerturber {
    private final PerturbConfig config;

    BenchmarkPerturber(PerturbConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    void perturb(Path inputDir, Path outputDir) throws IOException {
        List<GeneratedRow> rows = readGeneratedRows(inputDir.resolve("generated_molecules.tsv"));
        Random random = new Random(config.seed());
        Map<String, String> publicSeries = publicSeriesAssignments(rows, random);

        try (BufferedWriter publicWriter = Tsv.openWriter(outputDir.resolve("public_molecules.tsv"));
             BufferedWriter truthWriter = Tsv.openWriter(outputDir.resolve("perturbation_truth.tsv"))) {
            publicWriter.write("public_id\tproduct_smiles\tproduct_idcode\n");
            truthWriter.write("public_id\tgenerated_id\toriginal_series_id\tpublic_series_id\tperturbation\n");

            long publicIndex = 0;
            List<GeneratedRow> retained = new ArrayList<>();
            for (GeneratedRow row : rows) {
                if (random.nextDouble() < config.dropRate()) {
                    continue;
                }
                retained.add(row);
                String publicId = "mol_" + (++publicIndex);
                writePublic(publicWriter, publicId, row);
                writePerturbTruth(truthWriter, publicId, row, publicSeries.get(row.seriesId()), "retained");
            }

            int distractorCount = (int) Math.round(retained.size() * config.distractorRate());
            for (int i = 0; i < distractorCount && !rows.isEmpty(); i++) {
                GeneratedRow row = rows.get(random.nextInt(rows.size()));
                String publicId = "mol_" + (++publicIndex);
                writePublic(publicWriter, publicId, row);
                writePerturbTruth(truthWriter, publicId, row, "distractor", "distractor");
            }
        }
    }

    private Map<String, String> publicSeriesAssignments(List<GeneratedRow> rows, Random random) {
        TreeSet<String> series = new TreeSet<>();
        for (GeneratedRow row : rows) {
            series.add(row.seriesId());
        }
        Map<String, String> mapping = new HashMap<>();
        List<String> seriesList = new ArrayList<>(series);
        for (String seriesId : seriesList) {
            if (!mapping.containsKey(seriesId)) {
                mapping.put(seriesId, "public_" + seriesId);
            }
            if (seriesList.size() > 1 && random.nextDouble() < config.mergeRate()) {
                String other = seriesList.get(random.nextInt(seriesList.size()));
                mapping.put(seriesId, mapping.getOrDefault(other, "public_" + other));
            }
        }
        return mapping;
    }

    private static List<GeneratedRow> readGeneratedRows(Path input) throws IOException {
        List<GeneratedRow> rows = new ArrayList<>();
        try (BufferedReader reader = Tsv.openReader(input)) {
            Tsv.Header header = Tsv.Header.parse(reader.readLine());
            String line;
            while ((line = reader.readLine()) != null) {
                rows.add(GeneratedRow.fromTsv(header, line));
            }
        }
        return rows;
    }

    private static void writePublic(BufferedWriter writer, String publicId, GeneratedRow row) throws IOException {
        writer.write(String.join("\t", publicId, Tsv.clean(row.smiles()), Tsv.clean(row.idcode())));
        writer.write('\n');
    }

    private static void writePerturbTruth(BufferedWriter writer, String publicId, GeneratedRow row, String publicSeriesId, String perturbation)
            throws IOException {
        writer.write(String.join("\t", publicId, row.generatedId(), row.seriesId(), publicSeriesId, perturbation));
        writer.write('\n');
    }
}

final class CutEnumerator {
    private CutEnumerator() {}

    static List<CutResult> enumerate(SourceMolecule source, MiningConfig config) {
        StereoMolecule mol = source.copyMolecule();
        mol.ensureHelperArrays(Molecule.cHelperRings);
        List<Integer> eligible = eligibleBonds(mol);
        List<CutResult> results = new ArrayList<>();

        for (int bond : eligible) {
            addCut(source, mol, new int[]{bond}, config, results);
        }
        if (config.maxCuts() >= 2) {
            for (int i = 0; i < eligible.size(); i++) {
                for (int j = i + 1; j < eligible.size(); j++) {
                    int b1 = eligible.get(i);
                    int b2 = eligible.get(j);
                    if (shortestPathBetweenBonds(mol, b1, b2, new int[]{b1, b2}) > config.maxConnectorDistance() + 2) {
                        continue;
                    }
                    addCut(source, mol, new int[]{b1, b2}, config, results);
                }
            }
        }

        results.sort(Comparator.comparing(CutResult::sortKey));
        return results;
    }

    private static List<Integer> eligibleBonds(StereoMolecule mol) {
        List<Integer> bonds = new ArrayList<>();
        for (int bond = 0; bond < mol.getBonds(); bond++) {
            int a1 = mol.getBondAtom(0, bond);
            int a2 = mol.getBondAtom(1, bond);
            if (mol.getBondOrder(bond) != 1 || mol.isAromaticBond(bond) || mol.isRingBond(bond)) {
                continue;
            }
            if (!ordinaryHeavyAtom(mol, a1) || !ordinaryHeavyAtom(mol, a2)) {
                continue;
            }
            bonds.add(bond);
        }
        return bonds;
    }

    private static boolean ordinaryHeavyAtom(StereoMolecule mol, int atom) {
        int atomicNo = mol.getAtomicNo(atom);
        return atomicNo > 1 && atomicNo < 90 && mol.getAtomQueryFeatures(atom) == 0L;
    }

    private static void addCut(SourceMolecule source, StereoMolecule mol, int[] cutBonds, MiningConfig config, List<CutResult> results) {
        Arrays.sort(cutBonds);
        List<BitSet> components = connectedComponentsAfterCuts(mol, cutBonds);
        if (cutBonds.length == 1 && components.size() != 2) {
            return;
        }
        if (cutBonds.length == 2 && components.size() != 3) {
            return;
        }

        List<CutResult> candidates = new ArrayList<>();
        for (int[] labels : labelPermutations(cutBonds.length)) {
            List<SynthonComponent> parts = new ArrayList<>();
            for (BitSet component : components) {
                parts.add(SynthonComponent.fromComponent(mol, component, cutBonds, labels));
            }
            parts.sort(Comparator
                    .comparingInt(SynthonComponent::connectorCount).reversed()
                    .thenComparing(SynthonComponent::idcode));
            candidates.add(new CutResult(source.moleculeId(), cutBonds.length, parts));
        }
        CutResult best = candidates.stream().min(Comparator.comparing(CutResult::sortKey)).orElseThrow();
        if (isValid(best, mol, components, cutBonds, config)) {
            results.add(best);
        }
    }

    private static boolean isValid(CutResult cut, StereoMolecule mol, List<BitSet> components, int[] cutBonds, MiningConfig config) {
        if (cut.cutCount() == 1) {
            int h0 = cut.components().get(0).heavyAtoms();
            int h1 = cut.components().get(1).heavyAtoms();
            int min = Math.min(h0, h1);
            int max = Math.max(h0, h1);
            return min >= 1 && min <= config.maxTerminalHeavyAtoms() && max >= 8 && min <= (h0 + h1) * 0.4;
        }

        long twoConnector = cut.components().stream().filter(component -> component.connectorCount() == 2).count();
        long oneConnector = cut.components().stream().filter(component -> component.connectorCount() == 1).count();
        if (twoConnector != 1 || oneConnector != 2) {
            return false;
        }
        for (SynthonComponent component : cut.components()) {
            if (component.connectorCount() == 2) {
                if (component.heavyAtoms() < 2 || component.heavyAtoms() > config.maxMiddleHeavyAtoms()) {
                    return false;
                }
                BitSet originalComponent = components.stream()
                        .filter(bits -> component.sourceAtomKey().equals(bitSetKey(bits)))
                        .findFirst()
                        .orElse(null);
                if (originalComponent == null) {
                    return false;
                }
                int[] anchors = connectorAnchorAtoms(mol, originalComponent, cutBonds);
                if (anchors.length != 2 || shortestPath(mol, anchors[0], anchors[1], cutBonds) > config.maxConnectorDistance()) {
                    return false;
                }
            } else if (component.heavyAtoms() < 1 || component.heavyAtoms() > config.maxTerminalHeavyAtoms()) {
                return false;
            }
        }
        return true;
    }

    private static List<BitSet> connectedComponentsAfterCuts(StereoMolecule mol, int[] cutBonds) {
        boolean[] isCutBond = new boolean[mol.getBonds()];
        for (int bond : cutBonds) {
            isCutBond[bond] = true;
        }
        boolean[] seen = new boolean[mol.getAllAtoms()];
        List<BitSet> components = new ArrayList<>();
        for (int start = 0; start < mol.getAtoms(); start++) {
            if (mol.getAtomicNo(start) <= 1 || seen[start]) {
                continue;
            }
            BitSet component = new BitSet(mol.getAllAtoms());
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            seen[start] = true;
            component.set(start);
            while (!queue.isEmpty()) {
                int atom = queue.removeFirst();
                for (int i = 0; i < mol.getConnAtoms(atom); i++) {
                    int bond = mol.getConnBond(atom, i);
                    if (isCutBond[bond]) {
                        continue;
                    }
                    int neighbor = mol.getConnAtom(atom, i);
                    if (mol.getAtomicNo(neighbor) <= 1 || seen[neighbor]) {
                        continue;
                    }
                    seen[neighbor] = true;
                    component.set(neighbor);
                    queue.add(neighbor);
                }
            }
            components.add(component);
        }
        components.sort(Comparator.comparing(CutEnumerator::bitSetKey));
        return components;
    }

    private static int[] connectorAnchorAtoms(StereoMolecule mol, BitSet component, int[] cutBonds) {
        List<Integer> anchors = new ArrayList<>();
        for (int bond : cutBonds) {
            int a1 = mol.getBondAtom(0, bond);
            int a2 = mol.getBondAtom(1, bond);
            if (component.get(a1) != component.get(a2)) {
                anchors.add(component.get(a1) ? a1 : a2);
            }
        }
        return anchors.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int shortestPathBetweenBonds(StereoMolecule mol, int b1, int b2, int[] blockedBonds) {
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                best = Math.min(best, shortestPath(mol, mol.getBondAtom(i, b1), mol.getBondAtom(j, b2), blockedBonds));
            }
        }
        return best;
    }

    private static int shortestPath(StereoMolecule mol, int from, int to, int[] blockedBonds) {
        boolean[] blocked = new boolean[mol.getBonds()];
        for (int bond : blockedBonds) {
            blocked[bond] = true;
        }
        int[] distance = new int[mol.getAllAtoms()];
        Arrays.fill(distance, -1);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(from);
        distance[from] = 0;
        while (!queue.isEmpty()) {
            int atom = queue.removeFirst();
            if (atom == to) {
                return distance[atom];
            }
            for (int i = 0; i < mol.getConnAtoms(atom); i++) {
                int bond = mol.getConnBond(atom, i);
                if (blocked[bond]) {
                    continue;
                }
                int neighbor = mol.getConnAtom(atom, i);
                if (distance[neighbor] >= 0 || mol.getAtomicNo(neighbor) <= 1) {
                    continue;
                }
                distance[neighbor] = distance[atom] + 1;
                queue.add(neighbor);
            }
        }
        return Integer.MAX_VALUE;
    }

    private static List<int[]> labelPermutations(int cutCount) {
        if (cutCount == 1) {
            return List.of(new int[]{0});
        }
        return List.of(new int[]{0, 1}, new int[]{1, 0});
    }

    static String bitSetKey(BitSet bitSet) {
        StringBuilder sb = new StringBuilder();
        for (int atom = bitSet.nextSetBit(0); atom >= 0; atom = bitSet.nextSetBit(atom + 1)) {
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(atom);
        }
        return sb.toString();
    }
}

record SourceMolecule(
        String moleculeId,
        String sourceId,
        String idcode,
        String smiles,
        int heavyAtoms,
        double molecularWeight,
        int formalCharge
) {
    static SourceMolecule fromTsv(Tsv.Header header, String line) throws Exception {
        String[] fields = Tsv.split(line);
        String sourceId = firstPresent(header, fields, "chembl_id", "source_id", "molecule_id", "id");
        String idcode = firstPresent(header, fields, "idcode", "standardized_idcode");
        String smiles = firstPresent(header, fields, "smiles", "ocl_canonical_smiles", "canonical_smiles");
        StereoMolecule mol = new StereoMolecule();
        if (!idcode.isBlank()) {
            new IDCodeParser().parse(mol, idcode);
        } else if (!smiles.isBlank()) {
            new SmilesParser().parse(mol, smiles);
            idcode = new Canonizer(mol).getIDCode();
        } else {
            throw new IllegalArgumentException("No idcode or smiles");
        }
        mol.ensureHelperArrays(Molecule.cHelperCIP);
        if (smiles.isBlank()) {
            smiles = IsomericSmilesCreator.createSmiles(mol);
        }
        String moleculeId = sourceId.isBlank() ? "mol_" + Hashing.shortHash(idcode) : sourceId;
        return new SourceMolecule(
                moleculeId,
                sourceId.isBlank() ? moleculeId : sourceId,
                idcode,
                smiles,
                Chemistry.heavyAtomCount(mol),
                Chemistry.molecularWeight(mol),
                Chemistry.formalCharge(mol));
    }

    StereoMolecule copyMolecule() {
        StereoMolecule mol = new StereoMolecule();
        new IDCodeParser().parse(mol, idcode);
        mol.ensureHelperArrays(Molecule.cHelperCIP);
        return mol;
    }

    private static String firstPresent(Tsv.Header header, String[] fields, String... names) {
        for (String name : names) {
            String value = header.value(fields, name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}

record CutResult(String sourceMoleculeId, int cutCount, List<SynthonComponent> components) {
    CutResult {
        components = List.copyOf(components);
    }

    String sortKey() {
        StringBuilder sb = new StringBuilder();
        sb.append(cutCount).append('|');
        for (SynthonComponent component : components) {
            sb.append(component.idcode()).append('|').append(component.attachmentSignature()).append('|');
        }
        return sb.toString();
    }
}

record SynthonComponent(
        String idcode,
        int connectorCount,
        int heavyAtoms,
        double molecularWeight,
        String attachmentSignature,
        String sourceAtomKey
) {
    static SynthonComponent fromComponent(StereoMolecule mol, BitSet componentAtoms, int[] cutBonds, int[] labels) {
        StereoMolecule fragment = new StereoMolecule();
        boolean[] include = new boolean[mol.getAllAtoms()];
        for (int atom = componentAtoms.nextSetBit(0); atom >= 0; atom = componentAtoms.nextSetBit(atom + 1)) {
            include[atom] = true;
        }
        int[] oldToNew = new int[mol.getAllAtoms()];
        Arrays.fill(oldToNew, -1);
        mol.copyMoleculeByAtoms(fragment, include, true, oldToNew);

        List<Attachment> attachments = new ArrayList<>();
        for (int i = 0; i < cutBonds.length; i++) {
            int bond = cutBonds[i];
            int a1 = mol.getBondAtom(0, bond);
            int a2 = mol.getBondAtom(1, bond);
            if (componentAtoms.get(a1) == componentAtoms.get(a2)) {
                continue;
            }
            int included = componentAtoms.get(a1) ? a1 : a2;
            int host = included == a1 ? a2 : a1;
            int mapped = oldToNew[included];
            int dummy = fragment.addAtom(0);
            fragment.setAtomCustomLabel(dummy, "*" + labels[i]);
            fragment.addBond(mapped, dummy, Molecule.cBondTypeSingle);
            attachments.add(Attachment.from(mol, labels[i], host, included));
        }

        fragment.ensureHelperArrays(Molecule.cHelperRings);
        String idcode = new Canonizer(fragment, Canonizer.ENCODE_ATOM_CUSTOM_LABELS).getIDCode();
        attachments.sort(Comparator.comparingInt(Attachment::connectorIndex));
        return new SynthonComponent(
                idcode,
                attachments.size(),
                Chemistry.heavyAtomCount(fragment),
                Chemistry.molecularWeightWithoutConnectors(fragment),
                attachments.stream().map(Attachment::signature).reduce((a, b) -> a + ";" + b).orElse(""),
                CutEnumerator.bitSetKey(componentAtoms));
    }
}

record Attachment(
        int connectorIndex,
        int hostAtomicNo,
        boolean hostAromatic,
        int hostAtomPi,
        boolean hostRing,
        int fragmentAtomicNo,
        boolean fragmentAromatic,
        int fragmentAtomPi,
        boolean fragmentRing,
        int fragmentCharge
) {
    static Attachment from(StereoMolecule mol, int connectorIndex, int hostAtom, int fragmentAtom) {
        return new Attachment(
                connectorIndex,
                mol.getAtomicNo(hostAtom),
                mol.isAromaticAtom(hostAtom),
                mol.getAtomPi(hostAtom),
                mol.isRingAtom(hostAtom),
                mol.getAtomicNo(fragmentAtom),
                mol.isAromaticAtom(fragmentAtom),
                mol.getAtomPi(fragmentAtom),
                mol.isRingAtom(fragmentAtom),
                mol.getAtomCharge(fragmentAtom));
    }

    String signature() {
        return connectorIndex
                + "|" + atomSignature(hostAtomicNo, hostAromatic, hostAtomPi, hostRing, 0)
                + "--" + atomSignature(fragmentAtomicNo, fragmentAromatic, fragmentAtomPi, fragmentRing, fragmentCharge)
                + "|SINGLE";
    }

    private static String atomSignature(int atomicNo, boolean aromatic, int atomPi, boolean ring, int charge) {
        return atomicNo + "/" + (aromatic ? "Ar" : "sp" + atomPi) + "/" + (ring ? "R" : "N") + "/" + charge;
    }
}

final class ConnectorAssembler {
    private ConnectorAssembler() {}

    static StereoMolecule assemble(List<String> idcodes) {
        StereoMolecule assembled = new StereoMolecule();
        Map<String, List<Integer>> connectors = new HashMap<>();

        for (String idcode : idcodes) {
            StereoMolecule part = new StereoMolecule();
            new IDCodeParser().parse(part, idcode);
            part.ensureHelperArrays(Molecule.cHelperNeighbours);
            int[] map = new int[part.getAllAtoms()];
            assembled.addFragment(part, 0, map);
            for (int atom = 0; atom < part.getAtoms(); atom++) {
                String label = connectorLabel(part, atom);
                if (label != null) {
                    connectors.computeIfAbsent(label, key -> new ArrayList<>()).add(map[atom]);
                }
            }
        }

        assembled.ensureHelperArrays(Molecule.cHelperNeighbours);
        for (Map.Entry<String, List<Integer>> entry : connectors.entrySet()) {
            List<Integer> atoms = entry.getValue();
            if (atoms.size() != 2) {
                throw new IllegalArgumentException("Connector " + entry.getKey() + " has " + atoms.size() + " atoms");
            }
            int a = atoms.get(0);
            int b = atoms.get(1);
            if (assembled.getConnAtoms(a) != 1 || assembled.getConnAtoms(b) != 1) {
                throw new IllegalArgumentException("Connector atom must have exactly one neighbor");
            }
            int neighborA = assembled.getConnAtom(a, 0);
            int neighborB = assembled.getConnAtom(b, 0);
            assembled.addBond(neighborA, neighborB, Molecule.cBondTypeSingle);
            assembled.markAtomForDeletion(a);
            assembled.markAtomForDeletion(b);
        }
        assembled.deleteMarkedAtomsAndBonds();
        assembled.ensureHelperArrays(Molecule.cHelperCIP);
        return assembled;
    }

    private static String connectorLabel(StereoMolecule mol, int atom) {
        if (mol.getAtomicNo(atom) != 0) {
            return null;
        }
        String label = mol.getAtomCustomLabel(atom);
        if (label == null || !label.matches("\\*\\d+")) {
            return null;
        }
        return label;
    }
}

final class RoundTripValidator {
    private RoundTripValidator() {}

    static boolean matches(String originalIdcode, List<SynthonComponent> components) {
        try {
            StereoMolecule assembled = ConnectorAssembler.assemble(components.stream().map(SynthonComponent::idcode).toList());
            String assembledIdcode = new Canonizer(assembled).getIDCode();
            return originalIdcode.equals(assembledIdcode);
        } catch (Exception e) {
            return false;
        }
    }
}

final class Chemistry {
    private Chemistry() {}

    static int heavyAtomCount(StereoMolecule mol) {
        int count = 0;
        for (int atom = 0; atom < mol.getAtoms(); atom++) {
            int atomicNo = mol.getAtomicNo(atom);
            if (atomicNo > 1 && atomicNo < 90) {
                count++;
            }
        }
        return count;
    }

    static int formalCharge(StereoMolecule mol) {
        int charge = 0;
        for (int atom = 0; atom < mol.getAtoms(); atom++) {
            if (mol.getAtomicNo(atom) < 90) {
                charge += mol.getAtomCharge(atom);
            }
        }
        return charge;
    }

    static double molecularWeight(StereoMolecule mol) {
        return new MolecularFormula(mol).getRelativeWeight();
    }

    static double molecularWeightWithoutConnectors(StereoMolecule mol) {
        StereoMolecule copy = new StereoMolecule(mol);
        for (int atom = 0; atom < copy.getAtoms(); atom++) {
            int atomicNo = copy.getAtomicNo(atom);
            if (atomicNo == 0 || atomicNo >= 90) {
                copy.markAtomForDeletion(atom);
            }
        }
        copy.deleteMarkedAtomsAndBonds();
        return new MolecularFormula(copy).getRelativeWeight();
    }
}

final class MutableSynthon {
    private final String id;
    private final String idcode;
    private final int connectorCount;
    private final int heavyAtoms;
    private final double molecularWeight;
    private final Set<String> attachmentSignatures = new TreeSet<>();
    private final Set<String> exampleSourceIds = new LinkedHashSet<>();
    private int occurrenceCount;

    MutableSynthon(String id, SynthonComponent component) {
        this.id = id;
        this.idcode = component.idcode();
        this.connectorCount = component.connectorCount();
        this.heavyAtoms = component.heavyAtoms();
        this.molecularWeight = component.molecularWeight();
    }

    void addOccurrence(String sourceId, String attachmentSignature) {
        occurrenceCount++;
        attachmentSignatures.add(attachmentSignature);
        if (exampleSourceIds.size() < 8) {
            exampleSourceIds.add(sourceId);
        }
    }

    String id() { return id; }
    String idcode() { return idcode; }
    int connectorCount() { return connectorCount; }
    int heavyAtoms() { return heavyAtoms; }
    double molecularWeight() { return molecularWeight; }
    int occurrenceCount() { return occurrenceCount; }
    Set<String> attachmentSignatures() { return attachmentSignatures; }
    Set<String> exampleSourceIds() { return exampleSourceIds; }
}

final class MutableReplacementClass {
    private final String environmentId;
    private final int cutCount;
    private final List<String> fixedComponentIds;
    private final int variablePosition;
    private final String attachmentSignature;
    private final Set<String> possibleSynthonIds = new TreeSet<>();
    private int observationCount;

    MutableReplacementClass(String environmentId, int cutCount, List<String> fixedComponentIds, int variablePosition, String attachmentSignature) {
        this.environmentId = environmentId;
        this.cutCount = cutCount;
        this.fixedComponentIds = List.copyOf(fixedComponentIds);
        this.variablePosition = variablePosition;
        this.attachmentSignature = attachmentSignature;
    }

    void add(String synthonId) {
        possibleSynthonIds.add(synthonId);
        observationCount++;
    }

    String environmentId() { return environmentId; }
    int cutCount() { return cutCount; }
    List<String> fixedComponentIds() { return fixedComponentIds; }
    int variablePosition() { return variablePosition; }
    String attachmentSignature() { return attachmentSignature; }
    Set<String> possibleSynthonIds() { return possibleSynthonIds; }
    int observationCount() { return observationCount; }
}

record SynthonTableRecord(
        String synthonId,
        String idcode,
        int connectorCount,
        int heavyAtoms,
        double molecularWeight,
        Set<String> attachmentSignatures
) {
    static SynthonTableRecord fromTsv(Tsv.Header header, String line) {
        String[] fields = Tsv.split(line);
        return new SynthonTableRecord(
                header.required(fields, "synthon_id"),
                header.required(fields, "idcode"),
                Integer.parseInt(header.required(fields, "connector_count")),
                Integer.parseInt(header.required(fields, "heavy_atoms")),
                Double.parseDouble(header.required(fields, "molecular_weight")),
                new TreeSet<>(Tsv.splitEncoded(header.required(fields, "attachment_signatures"))));
    }

}

record CutInstanceRow(
        String cutInstanceId,
        int cutCount,
        List<String> componentIds,
        List<String> attachmentSignatures
) {
    static CutInstanceRow fromTsv(Tsv.Header header, String line) {
        String[] fields = Tsv.split(line);
        return new CutInstanceRow(
                header.required(fields, "cut_instance_id"),
                Integer.parseInt(header.required(fields, "cut_count")),
                Arrays.asList(header.required(fields, "component_synthon_ids").split("\\|", -1)),
                Tsv.splitEncoded(header.required(fields, "component_attachment_signatures")));
    }
}

record Product(String idcode, String smiles, int heavyAtoms, double molecularWeight, int formalCharge) {}

record GeneratedRow(String generatedId, String seriesId, String smiles, String idcode) {
    static GeneratedRow fromTsv(Tsv.Header header, String line) {
        String[] fields = Tsv.split(line);
        return new GeneratedRow(
                header.required(fields, "generated_id"),
                header.required(fields, "series_id"),
                header.required(fields, "product_smiles"),
                header.required(fields, "product_idcode"));
    }
}

final class SynthonIds {
    private SynthonIds() {}

    static String synthonId(String idcode) {
        return "syn_" + Hashing.shortHash(idcode);
    }

    static String cutInstanceId(String sourceId, int cutCount, List<String> synthonIds, List<String> signatures) {
        return "cut_" + Hashing.shortHash(sourceId + "|" + cutCount + "|" + String.join(",", synthonIds) + "|" + String.join(",", signatures));
    }

    static String environmentId(int cutCount, List<String> fixedIds, int variablePosition, String signature) {
        return "env_" + Hashing.shortHash(cutCount + "|" + String.join(",", fixedIds) + "|" + variablePosition + "|" + signature);
    }
}

final class Hashing {
    private Hashing() {}

    static String shortHash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

final class MiningStats {
    long scannedMolecules;
    long parseFailures;
    long enumerationFailures;
    long roundTripFailures;

    void write(Path output, int synthonCount, int replacementClassCount) throws IOException {
        try (BufferedWriter writer = Tsv.openWriter(output)) {
            writer.write("Scanned molecules: " + scannedMolecules + "\n");
            writer.write("Parse failures: " + parseFailures + "\n");
            writer.write("Enumeration failures: " + enumerationFailures + "\n");
            writer.write("Round-trip failures: " + roundTripFailures + "\n");
            writer.write("Synthons: " + synthonCount + "\n");
            writer.write("Replacement classes: " + replacementClassCount + "\n");
        }
    }
}

final class Tsv {
    private Tsv() {}

    static BufferedReader openReader(Path path) throws IOException {
        InputStream input = Files.newInputStream(path);
        if (path.toString().endsWith(".gz")) {
            input = new GZIPInputStream(input);
        }
        return new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    }

    static BufferedWriter openWriter(Path path) throws IOException {
        OutputStream output = Files.newOutputStream(path);
        if (path.toString().endsWith(".gz")) {
            output = new GZIPOutputStream(output);
        }
        return new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
    }

    static String[] split(String line) {
        return line.split("\t", -1);
    }

    static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    static String joinEncoded(Iterable<String> values) {
        List<String> encoded = new ArrayList<>();
        for (String value : values) {
            encoded.add(Base64.getUrlEncoder().withoutPadding().encodeToString(clean(value).getBytes(StandardCharsets.UTF_8)));
        }
        return String.join("|", encoded);
    }

    static List<String> splitEncoded(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> decoded = new ArrayList<>();
        for (String part : text.split("\\|", -1)) {
            decoded.add(new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8));
        }
        return decoded;
    }

    record Header(Map<String, Integer> indices) {
        static Header parse(String line) {
            String[] columns = split(line);
            Map<String, Integer> indices = new HashMap<>();
            for (int i = 0; i < columns.length; i++) {
                indices.put(columns[i], i);
            }
            return new Header(indices);
        }

        String value(String[] fields, String name) {
            Integer index = indices.get(name);
            if (index == null || index >= fields.length) {
                return null;
            }
            return fields[index];
        }

        String required(String[] fields, String name) {
            String value = value(fields, name);
            if (value == null) {
                throw new IllegalArgumentException("Missing column: " + name);
            }
            return value;
        }
    }
}
