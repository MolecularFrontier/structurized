package tech.molecules.structurized.mmp;

/** Outcome of attempting to apply one MMP transformation at one fragmentation site. */
public enum MmpTransformApplicationStatus {
    APPLIED,
    NOT_APPLICABLE,
    INVALID_TRANSFORM,
    INVALID_PRODUCT
}
