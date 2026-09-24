// Command-line front end for the Marcher checkers engine (github.com/Stermere/Checkers-Engine),
// so the app can run it as a process like its other engines. Line-based protocol on
// stdin/stdout, one command per line, one reply line per command:
//
//   search <p1> <p2> <p1k> <p2k> <player> <seconds> <depth> <forced>
//       -> move <from> <to> <eval> <depth>      (squares 0..63, row * 8 + col, row 0 on top)
//          or "move -1 -1 0 0" when the side to move has no move
//   perft <p1> <p2> <p1k> <p2k> <player> <depth>
//       -> perft <count>
//   isready -> readyok
//   quit
//
// Bitboards are unsigned 64-bit decimals; player 1 starts at the bottom and moves first.
// A multi-jump is played one jump per `search`: pass the square of the jumping piece as
// <forced> to continue it (-1 otherwise), exactly as the engine's Python and wasm hosts do.
//
// The engine sources are #included, as their own hosts do.

#include "src/src/engine/board_search.c"

#include <stdio.h>
#include <string.h>

int main(void) {
    char line[512];
    setvbuf(stdout, NULL, _IOLBF, 0);
    while (fgets(line, sizeof(line), stdin)) {
        unsigned long long p1, p2, p1k, p2k;
        int player, depth, forced;
        double seconds;
        if (sscanf(line, "search %llu %llu %llu %llu %d %lf %d %d",
                   &p1, &p2, &p1k, &p2k, &player, &seconds, &depth, &forced) == 8) {
            struct search_info* si = start_board_search((long long)p1, (long long)p2, (long long)p1k, (long long)p2k,
                                                        player, (float)seconds, depth, forced);
            int packed = si->best_move;
            int from = (packed >> 8) & 0xFF, to = packed & 0xFF;
            if (packed == 0 || from == to) { from = -1; to = -1; }
            printf("move %d %d %d %d\n", from, to, si->eval, si->evaler->search_depth);
            end_board_search(si->evaler);
            free(si);
        } else if (sscanf(line, "perft %llu %llu %llu %llu %d %d", &p1, &p2, &p1k, &p2k, &player, &depth) == 6) {
            long long lp1 = (long long)p1, lp2 = (long long)p2, lp1k = (long long)p1k, lp2k = (long long)p2k;
            struct set* piece_loc = get_piece_locations(lp1, lp2, lp1k, lp2k);
            char* offsets = compute_offsets();
            long long total = n_ply_search(&lp1, &lp2, &lp1k, &lp2k, player, piece_loc, offsets, depth);
            free(offsets);
            free(piece_loc);
            printf("perft %lld\n", total);
        } else if (strncmp(line, "isready", 7) == 0) {
            printf("readyok\n");
        } else if (strncmp(line, "quit", 4) == 0) {
            break;
        }
    }
    return 0;
}
