package tech.molecules.structurized.ai.search;

import com.actelion.research.chem.IsomericSmilesCreator;
import com.actelion.research.chem.Molecule;
import com.actelion.research.chem.SSSearcher;
import com.actelion.research.chem.SmilesParser;
import com.actelion.research.chem.StereoMolecule;
import tech.molecules.structurized.ai.model.AtomMapping;
import tech.molecules.structurized.ai.model.ChemOperationException;
import tech.molecules.structurized.ai.model.ExactStructureSearchMatch;
import tech.molecules.structurized.ai.model.ExactStructureSearchRequest;
import tech.molecules.structurized.ai.model.ExactStructureSearchResult;
import tech.molecules.structurized.ai.model.RepositoryRecord;
import tech.molecules.structurized.ai.model.SearchQuerySummary;
import tech.molecules.structurized.ai.model.SearchScope;
import tech.molecules.structurized.ai.model.SearchSummary;
import tech.molecules.structurized.ai.model.StructureRecord;
import tech.molecules.structurized.ai.model.SubstructureSearchMatch;
import tech.molecules.structurized.ai.model.SubstructureSearchRequest;
import tech.molecules.structurized.ai.model.SubstructureSearchResult;
import tech.molecules.structurized.ai.ocl.ComponentSnapshot;
import tech.molecules.structurized.ai.ocl.MolecularSnapshot;
import tech.molecules.structurized.ai.repository.StoredStructure;
import tech.molecules.structurized.ai.repository.StructureRepositoryService;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class OclStructureSearchService implements StructureSearchService {
    private static final String QUERY_TYPE_SMILES = "smiles";
    private static final String QUERY_TYPE_SMARTS = "smarts";
    private static final String EXACT_SCOPE_WHOLE_RECORD = "whole_record";
    private static final String EXACT_SCOPE_ANY_COMPONENT = "any_component";
    private static final String EXACT_SCOPE_LARGEST = "largest";
    private static final String SUBSTRUCTURE_SCOPE_ALL = "all";
    private static final String SUBSTRUCTURE_SCOPE_LARGEST = "largest";

    private final StructureRepositoryService repositories;

    public OclStructureSearchService(StructureRepositoryService repositories) {
        this.repositories = Objects.requireNonNull(repositories, "repositories");
    }

    @Override
    public ExactStructureSearchResult searchExactStructure(ExactStructureSearchRequest request) {
        Objects.requireNonNull(request, "request");
        String componentScope = normalizeExactScope(request.componentScope());
        MolecularSnapshot query = MolecularSnapshot.fromSmiles(request.querySmiles());
        List<ComponentIdentity> queryIdentities = exactQueryIdentities(query, componentScope);
        List<String> repositoryIds = resolveRepositoryIds(request.repositoryIds());

        List<ExactStructureSearchMatch> matches = new ArrayList<>();
        int structuresSearched = 0;
        Set<String> matchingStructures = new LinkedHashSet<>();
        for (String repositoryId : repositoryIds) {
            for (StructureRecord record : allStructures(repositoryId)) {
                structuresSearched++;
                StoredStructure stored = repositories.getStructure(record.ref());
                List<ComponentIdentity> targetIdentities = exactTargetIdentities(stored.snapshot(), componentScope);
                for (ComponentIdentity queryIdentity : queryIdentities) {
                    for (ComponentIdentity targetIdentity : targetIdentities) {
                        if (!queryIdentity.canonicalIdCode().equals(targetIdentity.canonicalIdCode())) {
                            continue;
                        }
                        matches.add(new ExactStructureSearchMatch(
                                record.repositoryId(),
                                record.structureId(),
                                record.label(),
                                targetIdentity.componentId()
                        ));
                        matchingStructures.add(record.repositoryId() + ":" + record.structureId());
                    }
                }
            }
        }

        return new ExactStructureSearchResult(
                new SearchQuerySummary(QUERY_TYPE_SMILES, request.querySmiles(), query.canonicalSmiles()),
                new SearchScope(repositoryIds, structuresSearched, componentScope),
                new SearchSummary(matchingStructures.size(), matchingStructures.size(), false),
                List.copyOf(matches),
                "OpenChemLib canonical IDCode equality; stereochemistry, isotopes, charges, and disconnected components are preserved; no tautomer, protonation, or salt normalization is applied."
        );
    }

    @Override
    public SubstructureSearchResult searchSubstructure(SubstructureSearchRequest request) {
        Objects.requireNonNull(request, "request");
        String queryType = normalizeQueryType(request.queryType());
        String componentScope = normalizeSubstructureScope(request.componentScope());
        int maxMatchesPerStructure = positiveOrDefault(request.maxMatchesPerStructure(), 1, "maxMatchesPerStructure");
        String outputMode = normalizeOutputMode(request.outputMode());
        int offset = Math.max(0, request.offset());
        int limit = positiveOrDefault(request.limit() > 0 ? request.limit() : request.maxResults(), 50, "limit");
        QueryMolecule query = parseSubstructureQuery(request.query(), queryType);
        List<String> repositoryIds = resolveRepositoryIds(request.repositoryIds());

        List<SubstructureSearchMatch> matches = new ArrayList<>();
        int structuresSearched = 0;
        int matchingStructures = 0;
        int matchingEntries = 0;
        boolean truncated = false;

        for (String repositoryId : repositoryIds) {
            for (StructureRecord record : allStructures(repositoryId)) {
                structuresSearched++;
                StoredStructure stored = repositories.getStructure(record.ref());
                boolean includeMappings = SubstructureSearchRequest.OUTPUT_FULL.equals(outputMode) && request.includeAtomMappings();
                List<SubstructureSearchMatch> structureMatches = searchStructure(
                        query.molecule(),
                        stored,
                        componentScope,
                        maxMatchesPerStructure,
                        includeMappings
                );
                if (structureMatches.isEmpty()) {
                    continue;
                }
                matchingStructures++;
                if (SubstructureSearchRequest.OUTPUT_COUNT.equals(outputMode)) {
                    continue;
                }
                for (SubstructureSearchMatch match : structureMatches) {
                    if (matchingEntries++ < offset) {
                        continue;
                    }
                    if (matches.size() >= limit) {
                        truncated = true;
                        continue;
                    }
                    matches.add(SubstructureSearchRequest.OUTPUT_IDS.equals(outputMode) ? compactMatch(match) : match);
                }
            }
        }
        if (!SubstructureSearchRequest.OUTPUT_COUNT.equals(outputMode) && matchingEntries > offset + limit) {
            truncated = true;
        }

        return new SubstructureSearchResult(
                new SearchQuerySummary(queryType, request.query(), query.normalized()),
                new SearchScope(repositoryIds, structuresSearched, componentScope),
                new SearchSummary(matchingStructures, matches.size(), truncated),
                List.copyOf(matches)
        );
    }

    private static String normalizeOutputMode(String outputMode) {
        if (outputMode == null || outputMode.isBlank()) {
            return SubstructureSearchRequest.OUTPUT_FULL;
        }
        String normalized = outputMode.trim().toLowerCase();
        if (SubstructureSearchRequest.OUTPUT_COUNT.equals(normalized)
                || SubstructureSearchRequest.OUTPUT_IDS.equals(normalized)
                || SubstructureSearchRequest.OUTPUT_FULL.equals(normalized)) {
            return normalized;
        }
        throw new ChemOperationException("invalid_output_mode", "substructure output_mode must be count, ids, or full.");
    }

    private static SubstructureSearchMatch compactMatch(SubstructureSearchMatch match) {
        return new SubstructureSearchMatch(
                match.repositoryId(),
                match.structureId(),
                match.label(),
                match.componentId(),
                match.matchCount(),
                List.of()
        );
    }

    private List<SubstructureSearchMatch> searchStructure(
            StereoMolecule query,
            StoredStructure stored,
            String componentScope,
            int maxMatchesPerStructure,
            boolean includeAtomMappings
    ) {
        MolecularSnapshot snapshot = stored.snapshot();
        List<ComponentSnapshot> components = selectComponents(snapshot, componentScope);
        List<SubstructureSearchMatch> result = new ArrayList<>();
        for (ComponentSnapshot component : components) {
            SSSearcher searcher = new SSSearcher();
            searcher.setMolecule(snapshot.moleculeView());
            searcher.setFragment(query);
            int matchCount = searcher.findFragmentInMolecule(
                    SSSearcher.cCountModeOverlapping,
                    SSSearcher.cDefaultMatchMode,
                    excludedAtoms(snapshot, component)
            );
            if (matchCount == 0) {
                continue;
            }
            List<AtomMapping> mappings = mappings(query, snapshot, searcher.getMatchList(), maxMatchesPerStructure);
            result.add(new SubstructureSearchMatch(
                    stored.record().repositoryId(),
                    stored.record().structureId(),
                    stored.record().label(),
                    component.componentId(),
                    matchCount,
                    includeAtomMappings ? mappings : List.of()
            ));
        }
        return List.copyOf(result);
    }

    private static List<AtomMapping> mappings(
            StereoMolecule query,
            MolecularSnapshot snapshot,
            List<int[]> rawMatches,
            int maxMatchesPerStructure
    ) {
        Map<String, AtomMapping> unique = new LinkedHashMap<>();
        for (int[] rawMatch : rawMatches) {
            List<String> targetAtomIds = new ArrayList<>();
            Map<String, String> queryToTarget = new LinkedHashMap<>();
            for (int queryAtom = 0; queryAtom < query.getAllAtoms(); queryAtom++) {
                String targetAtomId = snapshot.atomId(rawMatch[queryAtom]);
                queryToTarget.put("q" + (queryAtom + 1), targetAtomId);
                targetAtomIds.add(targetAtomId);
            }
            List<String> sortedTargetAtomIds = targetAtomIds.stream().sorted(atomIdComparator()).toList();
            String key = String.join("|", sortedTargetAtomIds);
            unique.putIfAbsent(key, new AtomMapping(Collections.unmodifiableMap(queryToTarget), sortedTargetAtomIds));
            if (unique.size() >= maxMatchesPerStructure) {
                break;
            }
        }
        return List.copyOf(unique.values());
    }

    private static boolean[] excludedAtoms(MolecularSnapshot snapshot, ComponentSnapshot component) {
        boolean[] excluded = new boolean[snapshot.atomCount()];
        BitSet included = new BitSet(snapshot.atomCount());
        for (int atom : component.atomIndices()) {
            included.set(atom);
        }
        for (int atom = 0; atom < excluded.length; atom++) {
            excluded[atom] = !included.get(atom);
        }
        return excluded;
    }

    private static List<ComponentSnapshot> selectComponents(MolecularSnapshot snapshot, String componentScope) {
        if (SUBSTRUCTURE_SCOPE_LARGEST.equals(componentScope) || EXACT_SCOPE_LARGEST.equals(componentScope)) {
            return List.of(snapshot.components().getFirst());
        }
        return snapshot.components();
    }

    private static List<ComponentIdentity> exactQueryIdentities(MolecularSnapshot query, String componentScope) {
        if (EXACT_SCOPE_WHOLE_RECORD.equals(componentScope)) {
            return List.of(new ComponentIdentity(null, query.canonicalIdCode()));
        }
        return selectComponents(query, componentScope).stream()
                .map(component -> new ComponentIdentity(component.componentId(), component.canonicalIdCode()))
                .toList();
    }

    private static List<ComponentIdentity> exactTargetIdentities(MolecularSnapshot target, String componentScope) {
        if (EXACT_SCOPE_WHOLE_RECORD.equals(componentScope)) {
            return List.of(new ComponentIdentity(null, target.canonicalIdCode()));
        }
        return selectComponents(target, componentScope).stream()
                .map(component -> new ComponentIdentity(component.componentId(), component.canonicalIdCode()))
                .toList();
    }

    private List<String> resolveRepositoryIds(List<String> requestedRepositoryIds) {
        List<String> repositoryIds = requestedRepositoryIds == null || requestedRepositoryIds.isEmpty()
                ? repositories.listRepositories().stream().map(RepositoryRecord::repositoryId).toList()
                : requestedRepositoryIds.stream().map(String::trim).filter(id -> !id.isEmpty()).toList();
        if (repositoryIds.isEmpty()) {
            throw new ChemOperationException("repository_not_found", "No repositories were selected for search.");
        }
        for (String repositoryId : repositoryIds) {
            allStructures(repositoryId);
        }
        return List.copyOf(repositoryIds);
    }

    private List<StructureRecord> allStructures(String repositoryId) {
        RepositoryRecord repository = repositories.listRepositories().stream()
                .filter(record -> record.repositoryId().equals(repositoryId))
                .findFirst()
                .orElseThrow(() -> new ChemOperationException("repository_not_found", "Repository " + repositoryId + " does not exist."));
        return repositories.listStructures(repositoryId, 0, Math.max(1, repository.structureCount()));
    }

    private static QueryMolecule parseSubstructureQuery(String query, String queryType) {
        if (query == null || query.isBlank()) {
            throw new ChemOperationException("query_parse_error", "Substructure query must not be null or blank.");
        }
        StereoMolecule molecule = new StereoMolecule();
        try {
            SmilesParser parser = QUERY_TYPE_SMARTS.equals(queryType)
                    ? new SmilesParser(SmilesParser.SMARTS_MODE_IS_SMARTS)
                    : new SmilesParser();
            parser.setRandomSeed(1L);
            parser.parse(molecule, query);
            molecule.setFragment(true);
            molecule.ensureHelperArrays(Molecule.cHelperCIP);
            String normalized = QUERY_TYPE_SMILES.equals(queryType)
                    ? new IsomericSmilesCreator(molecule).getSmiles()
                    : query;
            return new QueryMolecule(molecule, normalized);
        } catch (Exception e) {
            String code = QUERY_TYPE_SMARTS.equals(queryType) ? "query_parse_error" : "invalid_smiles";
            throw new ChemOperationException(code, "Could not parse " + queryType + " query: " + query, e);
        }
    }

    private static String normalizeQueryType(String queryType) {
        String normalized = queryType == null || queryType.isBlank() ? QUERY_TYPE_SMILES : queryType.trim().toLowerCase();
        if (!QUERY_TYPE_SMILES.equals(normalized) && !QUERY_TYPE_SMARTS.equals(normalized)) {
            throw new ChemOperationException("unsupported_query_type", "Unsupported query type: " + queryType);
        }
        return normalized;
    }

    private static String normalizeExactScope(String componentScope) {
        String normalized = componentScope == null || componentScope.isBlank() ? EXACT_SCOPE_WHOLE_RECORD : componentScope.trim();
        if (!EXACT_SCOPE_WHOLE_RECORD.equals(normalized)
                && !EXACT_SCOPE_ANY_COMPONENT.equals(normalized)
                && !EXACT_SCOPE_LARGEST.equals(normalized)) {
            throw new ChemOperationException("invalid_component_scope", "Invalid exact-structure component scope: " + componentScope);
        }
        return normalized;
    }

    private static String normalizeSubstructureScope(String componentScope) {
        String normalized = componentScope == null || componentScope.isBlank() ? SUBSTRUCTURE_SCOPE_ALL : componentScope.trim();
        if (!SUBSTRUCTURE_SCOPE_ALL.equals(normalized) && !SUBSTRUCTURE_SCOPE_LARGEST.equals(normalized)) {
            throw new ChemOperationException("invalid_component_scope", "Invalid substructure component scope: " + componentScope);
        }
        return normalized;
    }

    private static int positiveOrDefault(int value, int defaultValue, String name) {
        int normalized = value == 0 ? defaultValue : value;
        if (normalized < 1) {
            throw new ChemOperationException("result_limit_exceeded", name + " must be >= 1.");
        }
        return normalized;
    }

    private static Comparator<String> atomIdComparator() {
        return Comparator.comparingInt(atomId -> Integer.parseInt(atomId.substring(1)));
    }

    private record ComponentIdentity(String componentId, String canonicalIdCode) {}

    private record QueryMolecule(StereoMolecule molecule, String normalized) {}
}
