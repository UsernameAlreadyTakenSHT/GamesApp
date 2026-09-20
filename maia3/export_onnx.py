"""Exports a Maia-3 checkpoint to ONNX and dumps reference vectors for the Kotlin port.

Usage (inside a venv with torch, onnx, onnxruntime, python-chess and the maia3 package):
    python maia3/export_onnx.py [--model maia3-5m] [--out app/src/main/assets/nets/maia3-5m.onnx]

The ONNX graph takes:
    tokens    float32 (1, 64, 12 * history)   one-hot piece planes per square, side to move
                                              mirrored to white, oldest position first
    self_elo  int64   (1,)                     rating of the side to move
    oppo_elo  int64   (1,)
and returns move logits (1, 4352) over the vocabulary of utils.get_all_possible_moves()
plus value logits (1, 3) = [loss, draw, win].

reference.json holds tokens/logits for a few positions so the Android encoder can be
unit-tested against the Python one.
"""
import argparse
import json
import sys
from collections import deque
from pathlib import Path

import chess
import numpy as np
import onnxruntime as ort
import torch

from maia3.dataset import get_historical_tokens, get_legal_moves_mask, tokenize_board
from maia3.model_registry import resolve_checkpoint_path
from maia3.uci import load_model, parse_args
from maia3.utils import get_all_possible_moves, mirror_move


class Wrapper(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, tokens, self_elo, oppo_elo):
        logits_move, logits_value, _ = self.model(tokens, self_elo, oppo_elo)
        return logits_move, logits_value


def tokens_for(moves, cfg):
    """Same construction as the UCI wrapper in --use-uci-history mode."""
    board = chess.Board()
    history = deque(maxlen=cfg.history)
    history.append(tokenize_board(board))
    for uci in moves:
        board.push_uci(uci)
        history.append(tokenize_board(board))
    tokens = get_historical_tokens(history, cfg, base=0.0, inc=0.0, clk_left_before=0.0, clk_ponder=0.0)
    return board, tokens[:, :12 * cfg.history].contiguous()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="maia3-5m")
    ap.add_argument("--out", default=None)
    ap.add_argument("--reference", default=None)
    a = ap.parse_args()

    cfg = parse_args(["--model", a.model, "--device", "cpu", "--no-use-amp"])
    cfg.checkpoint_path = resolve_checkpoint_path(cfg.model_spec)
    print("checkpoint:", cfg.checkpoint_path, file=sys.stderr)
    model = load_model(cfg)
    wrapper = Wrapper(model).eval()

    root = Path(__file__).resolve().parent.parent
    out = Path(a.out) if a.out else root / "app/src/main/assets/nets" / f"{a.model}.onnx"
    ref_path = Path(a.reference) if a.reference else root / "maia3" / "reference.json"
    out.parent.mkdir(parents=True, exist_ok=True)

    dummy_tokens = torch.zeros((1, 64, 12 * cfg.history), dtype=torch.float32)
    dummy_elo = torch.tensor([1500], dtype=torch.long)
    with torch.no_grad():
        torch.onnx.export(
            wrapper,
            (dummy_tokens, dummy_elo, dummy_elo),
            str(out),
            input_names=["tokens", "self_elo", "oppo_elo"],
            output_names=["logits_move", "logits_value"],
            opset_version=18,
            dynamo=True,
        )
    # The dynamo exporter writes weights to a side file; fold them into a single .onnx.
    import onnx
    merged = onnx.load(str(out), load_external_data=True)
    onnx.save(merged, str(out), save_as_external_data=False)
    side = out.with_name(out.name + ".data")
    if side.exists():
        side.unlink()
    print("onnx:", out, out.stat().st_size, "bytes", file=sys.stderr)

    # ---- verification against the PyTorch model + reference dump ----------------------
    sess = ort.InferenceSession(str(out), providers=["CPUExecutionProvider"])
    all_moves = get_all_possible_moves()
    all_moves_dict = {m: i for i, m in enumerate(all_moves)}

    cases = [
        ([], 1500, 1500),
        (["e2e4"], 1200, 1200),
        (["e2e4", "e7e5", "g1f3", "b8c6", "f1b5"], 1900, 1900),
        (["d2d4", "d7d5", "c2c4", "e7e6", "b1c3", "g8f6", "c1g5", "f8e7", "e2e3", "e8g8"], 2300, 2300),
        (["e2e4", "c7c5", "g1f3", "d7d6", "d2d4", "c5d4", "f3d4", "g8f6", "b1c3", "a7a6", "c1e3", "e7e5",
          "d4b3", "c8e6", "f2f3", "b8d7"], 2600, 2600),
        # Black to move (mirrored encoding) and a promotion-capable position.
        (["e2e4", "e7e5", "g1f3"], 1100, 1100),
        (["a2a4", "b7b5", "a4b5", "a7a6", "b5a6", "c8b7", "a6b7", "g8f6"], 1500, 1500),
    ]
    reference = {"model": a.model, "history": cfg.history, "moves_vocab_size": len(all_moves), "cases": []}
    worst = 0.0
    for moves, self_elo, oppo_elo in cases:
        board, tokens = tokens_for(moves, cfg)
        t = tokens.unsqueeze(0)
        se = torch.tensor([self_elo], dtype=torch.long)
        oe = torch.tensor([oppo_elo], dtype=torch.long)
        with torch.no_grad():
            torch_logits, torch_value = wrapper(t, se, oe)
        onnx_logits, onnx_value = sess.run(None, {"tokens": t.numpy(), "self_elo": se.numpy(), "oppo_elo": oe.numpy()})
        worst = max(worst, float(np.abs(torch_logits.numpy() - onnx_logits).max()))

        mask = get_legal_moves_mask(board, all_moves_dict)
        logits = torch_logits[0].masked_fill(~mask, float("-inf"))
        probs = torch.softmax(logits, dim=-1)
        top = torch.topk(probs, k=5)
        top_moves = []
        for p, idx in zip(top.values.tolist(), top.indices.tolist()):
            mv = all_moves[idx]
            if board.turn == chess.BLACK:
                mv = mirror_move(mv)
            top_moves.append({"move": mv, "prob": round(p, 6), "index": idx})
        reference["cases"].append({
            "moves": moves,
            "self_elo": self_elo,
            "oppo_elo": oppo_elo,
            "tokens": tokens.to(torch.int8).flatten().tolist(),
            "top": top_moves,
            "value": [round(v, 6) for v in torch_value[0].tolist()],
        })
        print(moves, "->", top_moves[0]["move"], file=sys.stderr)

    print("max |torch - onnx| =", worst, file=sys.stderr)
    ref_path.write_text(json.dumps(reference))
    print("reference:", ref_path, file=sys.stderr)


if __name__ == "__main__":
    main()
