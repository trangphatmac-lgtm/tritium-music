#!/usr/bin/env python3
"""Development-only oracle generation using official MNN; never a runtime dependency."""
import argparse
import hashlib
import importlib.metadata
import json
from pathlib import Path
import subprocess
import sys
import tempfile

import MNN
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
MODEL_HASH = "a5537bea1958bd27202cba2fdf3754858cb788e0bc18366c6ecc9894b05236d1"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=Path, default=ROOT / "analysis/netease-9.5.40/model-acquisition/weights/c2e9eb7c406c2eb71c93329375d43aa8/emofxnet.mnn")
    parser.add_argument("--output", type=Path, default=ROOT / "src/test/resources/tritium/emotion-model-oracle.json")
    args = parser.parse_args()
    assert hashlib.sha256(args.model.read_bytes()).hexdigest() == MODEL_HASH
    source = ROOT / "analysis/netease-9.5.40/model-acquisition/diagnostics/model-input-fixture.json"
    fixture = json.loads(source.read_text())
    with tempfile.TemporaryDirectory(prefix="tritium-mnn-graph-") as directory:
        graph_path = Path(directory) / "graph.json"
        converter = Path(sys.executable).with_name("mnnconvert")
        subprocess.run([str(converter), "-f", "MNN", "--modelFile", str(args.model),
                        "--JsonFile", str(graph_path)], check=True)
        graph = json.loads(graph_path.read_text())
    constants = {op["name"]: np.asarray(op["main"]["float32s"], dtype=np.float32)
                 for op in graph["oplists"] if "float32s" in op.get("main", {})}
    mean = constants["feature_mean"]
    invstd = constants["Const49"]
    std = np.divide(1., invstd, out=np.ones_like(invstd), where=invstd != 0)
    low = constants["aev2_min"]
    span = constants["onnx::Mul_131"]
    cases = []

    def add(name, emotion, genre, features, base):
        cases.append(dict(name=name, emotion=int(emotion), genre=int(genre),
                          features=np.asarray(features, dtype=np.float32).tolist(),
                          base=np.asarray(base, dtype=np.float32).tolist()))

    for name, inputs in [("real_apk_override_calm", fixture["inputs"]),
                         ("real_without_override_energetic", fixture["alternative_without_override"]["inputs"])]:
        add(name, inputs["emotion_idx"]["data"][0], inputs["genre_idx"]["data"][0],
            inputs["features"]["data"], inputs["base_extra_flat"]["data"])
    rng = np.random.default_rng(20261031)
    for emotion in range(16):
        for genre in range(4):
            add(f"vocabulary_{emotion}_{genre}_zero_base", emotion, genre,
                mean + rng.normal(0., 1., 64).astype(np.float32) * std, np.zeros(32))
    for i in range(32):
        add(f"random_{i}", 1 + i % 15, i % 4,
            mean + rng.normal(0., 3., 64).astype(np.float32) * std,
            low + rng.uniform(0., 1., 32).astype(np.float32) * span)
    for label, features in [("zero_features", np.zeros(64)), ("mean_features", mean),
                            ("high_features", mean + 8 * std), ("low_features", mean - 8 * std)]:
        for base_label, base in [("min", low), ("max", low + span),
                                 ("below_min", low - span), ("above_max", low + 2 * span)]:
            add(f"boundary_{label}_{base_label}", 15, 3, features, base)
    for magnitude in [1e-6, 1e-5, 1e-4]:
        base = np.zeros(32, dtype=np.float32)
        base[0] = magnitude
        add(f"base_sum_threshold_{magnitude}", 7, 2, mean, base)
    add("emotion_zero_masks_network", 0, 3, mean + 8 * std, low + span * .4)

    interpreter = MNN.Interpreter(str(args.model))
    session = interpreter.createSession({"backend": "CPU", "numThread": 1})
    inputs = interpreter.getSessionInputAll(session)
    output = interpreter.getSessionOutput(session, "aev2")
    for case in cases:
        arrays = {"emotion_idx": np.asarray([case["emotion"]], dtype=np.int32),
                  "genre_idx": np.asarray([case["genre"]], dtype=np.int32),
                  "features": np.asarray([case["features"]], dtype=np.float32),
                  "base_extra_flat": np.asarray([case["base"]], dtype=np.float32)}
        hosts = []
        for name, array in arrays.items():
            tensor = inputs[name]
            dtype = MNN.Halide_Type_Int if array.dtype == np.int32 else MNN.Halide_Type_Float
            host = MNN.Tensor(tuple(array.shape), dtype, array, tensor.getDimensionType())
            hosts.append(host)
            tensor.copyFrom(host)
        assert interpreter.runSession(session) == 0
        values = np.asarray(output.getData(), dtype=np.float32).copy()
        assert values.size == 32 and np.isfinite(values).all(), case["name"]
        case["expected"] = values.tolist()
    result = dict(modelSha256=MODEL_HASH, runtime="official MNN CPU",
                  mnnVersion=importlib.metadata.version("MNN"), numpyVersion=np.__version__,
                  seed=20261031, cases=cases)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(f"Wrote {len(cases)} official MNN cases to {args.output}")


if __name__ == "__main__":
    main()
