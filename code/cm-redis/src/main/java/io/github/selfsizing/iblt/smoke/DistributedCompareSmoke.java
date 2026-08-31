package io.github.selfsizing.iblt.smoke;

import io.github.selfsizing.iblt.controller.DefaultIbltCompareController;
import io.github.selfsizing.iblt.controller.HttpSidecarClient;
import io.github.selfsizing.iblt.controller.IbltCompareController;
import io.github.selfsizing.iblt.controller.IbltComparePlan;
import io.github.selfsizing.iblt.controller.IbltCompareResult;
import io.github.selfsizing.iblt.controller.SidecarClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;

/**
 * Distributed cross-machine comparison smoke test (an experiment tool, not part
 * of the shipped module): each of two machines runs an {@code IbltSidecarServer},
 * the controller reaches both through {@code ssh -L} tunnels with
 * {@link HttpSidecarClient}, runs the full {@link DefaultIbltCompareController}
 * pipeline, and verifies that "after exchanging sketches the 22 difference pks
 * can be recovered".
 *
 * <p>Usage: {@code DistributedCompareSmoke <urlA> <urlB> [truth_ids.txt] [valueSampleLimit]},
 * e.g. {@code http://127.0.0.1:19011 http://127.0.0.1:19012 truth_ids.txt 100}
 * ({@code valueSampleLimit} omitted or <=0 = fetch all row values).</p>
 */
public final class DistributedCompareSmoke {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: DistributedCompareSmoke <urlA> <urlB> "
                    + "[truth_ids.txt] [valueSampleLimit] [--truth <file>] "
                    + "[--value-sample-limit <n>] [--expect-equal]");
            System.exit(2);
        }
        SidecarClient a = new HttpSidecarClient("A", args[0]);
        SidecarClient b = new HttpSidecarClient("B", args[1]);
        Path truthPath = null;
        int valueSampleLimit = 0;
        boolean sampleLimitSet = false;
        boolean expectEqual = false;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--truth":
                    if (++i >= args.length) throw new IllegalArgumentException("--truth requires a file");
                    truthPath = Path.of(args[i]);
                    break;
                case "--value-sample-limit":
                    if (++i >= args.length) {
                        throw new IllegalArgumentException("--value-sample-limit requires an integer");
                    }
                    valueSampleLimit = Integer.parseInt(args[i]);
                    sampleLimitSet = true;
                    break;
                case "--expect-equal":
                    expectEqual = true;
                    break;
                default:
                    // Backward compatible positional form: [truth_ids.txt] [valueSampleLimit].
                    if (truthPath == null) {
                        truthPath = Path.of(args[i]);
                    } else if (!sampleLimitSet) {
                        valueSampleLimit = Integer.parseInt(args[i]);
                        sampleLimitSet = true;
                    } else {
                        throw new IllegalArgumentException("unknown argument: " + args[i]);
                    }
            }
        }

        System.out.println("[smoke] A.health: " + a.health());
        System.out.println("[smoke] B.health: " + b.health());

        IbltComparePlan plan = IbltComparePlan.defaults().withValueSampleLimit(valueSampleLimit);
        IbltCompareController controller = new DefaultIbltCompareController();

        long t0 = System.nanoTime();
        IbltCompareResult r = controller.compare(a, b, plan);
        long elapsed = Math.round((System.nanoTime() - t0) / 1e6);

        System.out.printf("[smoke] decode success=%b at M=%d, plus=%d, minus=%d, residual=%d, dHat=%.1f%n",
                r.isSuccess(), r.bucketCountAtDecode(), r.plusCount(), r.minusCount(),
                r.residualBuckets(), r.dHat());
        System.out.printf("[smoke] sketch exchanged: A=%dB, B=%dB (small control payload)%n",
                r.sketchWireBytesA(), r.sketchWireBytesB());
        System.out.printf("[smoke] diff pks (full)=%d: %s%n", r.candidatePks().size(), r.candidatePks());
        System.out.printf("[smoke] value fetch: sampled=%d (limit=%s), recheck A hit %d, B hit %d%n",
                r.sampledPkCount(), valueSampleLimit <= 0 ? "all" : String.valueOf(valueSampleLimit),
                r.recheckA() == null ? 0 : r.recheckA().rowCount(),
                r.recheckB() == null ? 0 : r.recheckB().rowCount());
        System.out.printf("[smoke] controller end-to-end: %dms%n", elapsed);

        int exit = r.isSuccess() ? 0 : 1;
        if (truthPath != null) {
            // compare primary keys as raw text: works for existing BIGINT database primary keys and for Redis string keys.
            TreeSet<String> truth = new TreeSet<>();
            for (String line : Files.readAllLines(truthPath)) {
                String s = line.trim();
                if (!s.isEmpty()) {
                    truth.add(s);
                }
            }
            TreeSet<String> got = new TreeSet<>(r.candidatePks());
            boolean match = got.equals(truth);
            System.out.printf("[smoke] ground-truth pks=%d, diff-pk match=%b%n", truth.size(), match);
            if (!match) {
                exit = 1;
            }
        } else if (expectEqual) {
            boolean equal = r.candidatePks().isEmpty();
            System.out.printf("[smoke] expect-equal=%b%n", equal);
            if (!equal) {
                exit = 1;
            }
        }
        System.out.println(exit == 0 ? "[smoke] RESULT: PASS" : "[smoke] RESULT: FAIL");
        System.exit(exit);
    }

    private DistributedCompareSmoke() {
    }
}
