package souther.wasm.link;

import souther.wasm.abi.RuntimeAbi;
import souther.wasm.link.RuntimeLayout.Export;
import souther.wasm.link.RuntimeLayout.ExportKind;

/**
 * Where a generated definition goes, read off the runtime before any of it is written.
 *
 * <p>A generated function is emitted at the index it will hold in the linked module rather than at
 * zero and moved afterwards. Wasm gives functions, types, tables, memories and globals index
 * spaces of their own, and an import takes a lower index than any definition, so what a body may
 * write as a call operand is only known once the runtime has been read. That reading is this.
 */
public final class LinkPlan {

    private final byte[] runtime;
    private final RuntimeLayout layout;

    private LinkPlan(byte[] runtime, RuntimeLayout layout) {
        this.runtime = runtime;
        this.layout = layout;
    }

    /**
     * Reads the runtime a link will build on.
     *
     * @param runtime the compiled runtime module
     */
    public static LinkPlan reading(byte[] runtime) {
        RuntimeLayout layout = RuntimeLayout.of(runtime);
        if (layout.export(RuntimeAbi.RUNTIME_INIT).isEmpty()) {
            throw new IllegalArgumentException(
                    "this module exports no " + RuntimeAbi.RUNTIME_INIT + ", so a link could not place its arena");
        }
        return new LinkPlan(runtime, layout);
    }

    /** The runtime module the link builds on. */
    public byte[] runtime() {
        return runtime.clone();
    }

    /** What the runtime occupies. */
    public RuntimeLayout layout() {
        return layout;
    }

    /** The index the first generated function takes. */
    public int firstGeneratedFunctionIndex() {
        return layout.firstGeneratedFunctionIndex();
    }

    /** The index the first generated type takes. */
    public int firstGeneratedTypeIndex() {
        return layout.firstGeneratedTypeIndex();
    }

    /** The first address a generated data segment may be placed at. */
    public int staticBase() {
        return layout.heapBase(runtime);
    }

    /**
     * The index of a function the runtime exports, for a generated body to call.
     *
     * @param export the export's name, from {@link RuntimeAbi}
     */
    public int functionIndexOf(String export) {
        Export found = layout.export(export).orElseThrow(() -> new IllegalArgumentException(
                "the runtime exports no " + export));
        if (found.kind() != ExportKind.FUNCTION) {
            throw new IllegalArgumentException(export + " is exported, but not as a function");
        }
        return found.index();
    }
}
