package repackage.processing.sound;

import repackage.com.jsyn.ports.UnitOutputPort;
import repackage.com.jsyn.ports.UnitInputPort;
import repackage.com.jsyn.unitgen.*;

/**
 * Helper class wrapping a source unit generator, add/pan processor and effect 
 * into one circuit.
 */
public class JSynCircuit extends Circuit implements UnitSource {

	private UnitGenerator source;
	protected JSynProcessor processor;
	protected UnitOutputPort preEffect;
	protected Effect<? extends UnitFilter> effect;
	protected TwoInDualOut output;
	private boolean hasStereoStage;
	private volatile double outputGain = 1;

	public JSynCircuit(UnitOutputPort input) {
		this.output = new TwoInDualOut() {
			@Override public void generate(int start, int limit) {
				super.generate(start, limit);
				double gain = outputGain;
				if (gain == 1) return;
				double[] left = output.getValues(0), right = output.getValues(1);
				for (int i = start; i < limit; i++) { left[i] *= gain; right[i] *= gain; }
			}
		};
		this.add(this.output);

		this.source = input.getUnitGenerator();
		this.add(this.source);

		if (input.getNumParts() == 2) {
			// stereo source - no need for pan, so bypass processor
			this.preEffect = input;
		} else {
			this.processor = new JSynProcessor();
			this.add(this.processor);
			this.processor.input.connect(input);
			this.preEffect = this.processor.output;
		}
		this.wireBypass();
	}

	/** Install once while constructing a source, before it is published to playback.
	 * This persistent stereo stage precedes the independently removable Effect slot. */
	public void installStereoStage(UnitInputPort input, UnitOutputPort result) {
		if (hasStereoStage || effect != null || getSynthesizer() != null)
			throw new IllegalStateException("Stereo stage must be installed before playback");
		if (input.getNumParts() != 2 || result.getNumParts() != 2
				|| input.getUnitGenerator() != result.getUnitGenerator())
			throw new IllegalArgumentException("A stereo unit is required");
		preEffect.disconnect(0, output.inputA, 0);
		preEffect.disconnect(1, output.inputB, 0);
		add(result.getUnitGenerator());
		preEffect.connect(0, input, 0);
		preEffect.connect(1, input, 1);
		preEffect = result;
		hasStereoStage = true;
		wireBypass();
	}

	/** Final gain, after persistent DSP and the removable effect (including their tails). */
	public void setOutputGain(double gain) {
		outputGain = Double.isFinite(gain) ? Math.max(0, Math.min(1, gain)) : 0;
	}

	protected void wireBypass() {
		this.preEffect.connect(0, this.output.inputA, 0);
		this.preEffect.connect(1, this.output.inputB, 0);
	}

	protected void removeEffect() {
		if (this.effect != null) {
			this.wireBypass();
			this.effect.left.output.disconnect(this.output.inputA);
			this.effect.right.output.disconnect(this.output.inputB);
			this.effect.left.input.disconnect(0, this.preEffect, 0);
			this.effect.right.input.disconnect(0, this.preEffect, 1);
			this.effect = null;
		}
	}

	protected void setEffect(Effect<? extends UnitFilter> effect) {
		this.effect = effect;
		this.preEffect.connect(0, this.effect.left.input, 0);
		this.preEffect.connect(1, this.effect.right.input, 0);

		this.effect.left.output.connect(this.output.inputA);
		this.preEffect.disconnect(0, this.output.inputA, 0);

		this.effect.right.output.connect(this.output.inputB);
		this.preEffect.disconnect(1, this.output.inputB, 0);
	}

	@Override
	public UnitOutputPort getOutput() {
		return this.output.output;
	}
}
