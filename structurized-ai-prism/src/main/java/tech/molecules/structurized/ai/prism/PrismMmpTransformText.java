package tech.molecules.structurized.ai.prism;

public record PrismMmpTransformText(
        String transformId,
        Integer cutCount,
        String keyFragment,
        String fromFragment,
        String toFragment,
        String transformText
) {
}
