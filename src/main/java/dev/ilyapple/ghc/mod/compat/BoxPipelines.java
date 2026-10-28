package dev.ilyapple.ghc.mod.compat;

import java.util.Optional;

//? if >= 26.3 {
/*import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
*///?} elif >= 26.2 {
/*import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.VertexFormat;
*///?} else {
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.VertexFormat;
//?}

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * Line and filled-box render types, each with the depth check and without it: Parallax's {@code ParallaxPipelines},
 * {@code ParallaxPipelineBuilder}, {@code ParallaxPipelineDepth} and {@code DualParallaxLevelRenderType} (Athen's
 * renderer, github.com/skies-starred/Parallax, BSD-3-Clause, © Starred), in Java. Depth is reversed since 26.2, so
 * "less or equal" is {@code GREATER_THAN_OR_EQUAL} there; 26.3 moved the pipeline classes to
 * {@code com.mojang.renderpearl}, and a pipeline needs a colour target the render pass can draw to.
 */
public final class BoxPipelines {
	public static final RenderType LINES_DEPTH = type("lines", pipeline(RenderPipelines.LINES_SNIPPET, "depth/line", true, true));
	public static final RenderType LINES_DEPTHLESS = type("lines_depthless", pipeline(RenderPipelines.LINES_SNIPPET, "depthless/line", false, true));
	public static final RenderType FILLED_DEPTH = type("debug_filled", pipeline(RenderPipelines.DEBUG_FILLED_SNIPPET, "depth/debug_filled", true, false));
	public static final RenderType FILLED_DEPTHLESS = type("debug_filled_depthless", pipeline(RenderPipelines.DEBUG_FILLED_SNIPPET, "depthless/debug_filled", false, false));

	private BoxPipelines() {}

	/** Loads the class, registering the pipelines early. */
	public static void init() {
	}

	private static RenderPipeline pipeline(RenderPipeline.Snippet snippet, String location, boolean depth, boolean cull) {
		RenderPipeline.Builder builder = RenderPipeline.builder(snippet).withLocation("greenhouse-companion/" + location);
		//? if >= 26.2 {
		/*VertexFormat[] formats = snippet.vertexFormatPerBuffer();
		VertexFormat format = formats.length > 0 ? formats[0] : null;
		PrimitiveTopology mode = snippet.vertexFormatMode().orElse(null);
		if (format != null) {
			builder.withVertexBinding(0, format);
			builder.withPrimitiveTopology(mode != null ? mode : PrimitiveTopology.QUADS);
		}
		// ParallaxPipelineDepth: LEQUAL with the depth check (reversed depth), none without
		builder.withDepthStencilState(depth ? Optional.of(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false)) : Optional.empty());
		*///?} else {
		VertexFormat format = snippet.vertexFormat().orElse(null);
		VertexFormat.Mode mode = snippet.vertexFormatMode().orElse(null);
		if (format != null) builder.withVertexFormat(format, mode != null ? mode : VertexFormat.Mode.QUADS);
		// ParallaxPipelineDepth: LEQUAL with the depth check, none without
		builder.withDepthStencilState(depth ? Optional.of(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false)) : Optional.empty());
		//?}
		if (!cull) builder.withCull(false);
		// A snippet with no colour target gets a translucent one, as Parallax does on 26.3
		//? if >= 26.3
		/*if (snippet.activeColorTargetStateCount() == 0) builder.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT));*/
		return RenderPipelines.register(builder.build());
	}

	private static RenderType type(String name, RenderPipeline pipeline) {
		//? if >= 26.2 {
		/*return RenderType.create("greenhouse_companion_" + name, RenderSetup.builder(pipeline).createRenderSetup());
		*///?} else
		return RenderType.create("greenhouse_companion_" + name, RenderSetup.builder(pipeline).bufferSize(RenderType.TRANSIENT_BUFFER_SIZE).createRenderSetup());
	}
}
