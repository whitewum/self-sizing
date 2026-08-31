#include "core/estimators/plain_f2_estimator.h"
#include "core/iblt/standard_iblt.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdlib>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace {

struct Options {
    int trials = 200;
    int seed0 = 2026;
    int max_retries = 30;
    int threads = static_cast<int>(std::max(1u, std::thread::hardware_concurrency()));
    std::vector<int> m_list = {256, 1024, 4096};
    std::vector<int> k_list = {3};
    std::vector<double> d_over_m_list = {0.5, 0.7, 0.8, 0.9, 1.0, 1.2, 1.5, 2.0, 5.0, 10.0};
    std::vector<double> neg_ratios = {0.5, 0.1, 0.9};
    std::vector<double> gamma_list;
    std::string out_path;
};

struct Point {
    int point_id = 0;
    int M = 0;
    int k = 0;
    int d = 0;
    double d_over_m = 0.0;
    double neg_ratio = 0.0;
};

bool has_arg(int argc, char** argv, const std::string& name) {
    for (int i = 1; i < argc; ++i) {
        if (argv[i] == name) {
            return true;
        }
    }
    return false;
}

std::string arg_string(int argc, char** argv, const std::string& name, const std::string& fallback) {
    for (int i = 1; i + 1 < argc; ++i) {
        if (argv[i] == name) {
            return argv[i + 1];
        }
    }
    return fallback;
}

int arg_int(int argc, char** argv, const std::string& name, int fallback) {
    const std::string value = arg_string(argc, argv, name, "");
    return value.empty() ? fallback : std::atoi(value.c_str());
}

std::vector<std::string> split_csv(const std::string& text) {
    std::vector<std::string> out;
    std::string item;
    std::stringstream ss(text);
    while (std::getline(ss, item, ',')) {
        if (!item.empty()) {
            out.push_back(item);
        }
    }
    return out;
}

std::vector<int> parse_int_list(const std::string& text) {
    std::vector<int> values;
    for (const auto& item : split_csv(text)) {
        values.push_back(std::atoi(item.c_str()));
    }
    return values;
}

std::vector<double> parse_double_list(const std::string& text) {
    std::vector<double> values;
    for (const auto& item : split_csv(text)) {
        values.push_back(std::atof(item.c_str()));
    }
    return values;
}

std::string format_double(double value) {
    std::ostringstream oss;
    oss << std::setprecision(17) << value;
    return oss.str();
}

void fill_diff(sciblt::StandardIBLT& alice, sciblt::StandardIBLT& bob, int d, double neg_ratio) {
    const int d_minus = std::max(0, std::min(d, static_cast<int>(std::llround(d * neg_ratio))));
    const int d_plus = d - d_minus;

    for (int x = 0; x < d_plus; ++x) {
        alice.insert(x);
    }
    for (int x = d_plus; x < d_plus + d_minus; ++x) {
        bob.insert(x);
    }
}

std::string build_row(const Point& point,
                      int trial,
                      int seed,
                      const sciblt::PlainF2Stats& stats,
                      const sciblt::DecodeResult& dec1,
                      const std::string& gamma_field,
                      const std::string& m2_field,
                      const std::string& ok2_field) {
    const double ratio = point.d > 0 ? stats.d_hat / static_cast<double>(point.d) : 0.0;

    std::ostringstream row;
    row << point.point_id << ','
        << trial << ','
        << point.M << ','
        << point.k << ','
        << point.d << ','
        << format_double(point.d_over_m) << ','
        << format_double(point.neg_ratio) << ','
        << gamma_field << ','
        << seed << ','
        << (dec1.success ? 1 : 0) << ','
        << dec1.recovered_count << ','
        << stats.s_net << ','
        << stats.s_sq << ','
        << format_double(stats.e_global) << ','
        << format_double(stats.c_theory) << ','
        << format_double(stats.d_hat) << ','
        << format_double(ratio) << ','
        << m2_field << ','
        << ok2_field << ',';
    return row.str();
}

std::vector<std::string> make_rows(const Point& point,
                                   int trial,
                                   int seed,
                                   int max_retries,
                                   const std::vector<double>& gamma_list) {
    try {
        sciblt::StandardIBLT alice1(point.M, point.k, seed);
        sciblt::StandardIBLT bob1(point.M, point.k, seed);
        fill_diff(alice1, bob1, point.d, point.neg_ratio);
        auto diff1 = alice1.subtract(bob1);

        const auto stats = sciblt::estimate_plain_f2_from_counts(diff1);
        const auto dec1 = diff1.decode(true, max_retries, false, static_cast<unsigned int>(seed));

        if (gamma_list.empty()) {
            return {build_row(point, trial, seed, stats, dec1, "", "", "")};
        }

        std::vector<std::string> rows;
        rows.reserve(gamma_list.size());
        for (double gamma : gamma_list) {
            std::string m2_field;
            std::string ok2_field;

            if (!dec1.success) {
                const int M2 = std::max(point.k, static_cast<int>(std::ceil(gamma * stats.d_hat)));
                const int seed2 = seed + 7919;
                sciblt::StandardIBLT alice2(M2, point.k, seed2);
                sciblt::StandardIBLT bob2(M2, point.k, seed2);
                fill_diff(alice2, bob2, point.d, point.neg_ratio);
                auto diff2 = alice2.subtract(bob2);
                const auto dec2 = diff2.decode(true, max_retries, false, static_cast<unsigned int>(seed2));
                m2_field = std::to_string(M2);
                ok2_field = dec2.success ? "1" : "0";
            }

            rows.push_back(build_row(point,
                                     trial,
                                     seed,
                                     stats,
                                     dec1,
                                     format_double(gamma),
                                     m2_field,
                                     ok2_field));
        }
        return rows;
    } catch (const std::exception& ex) {
        std::ostringstream row;
        row << point.point_id << ','
            << trial << ','
            << point.M << ','
            << point.k << ','
            << point.d << ','
            << format_double(point.d_over_m) << ','
            << format_double(point.neg_ratio) << ','
            << ','
            << seed << ",,,,,,,,,,,,"
            << ex.what();
        return {row.str()};
    }
}

void print_usage(const char* argv0) {
    std::cerr
        << "Usage: " << argv0 << " [options]\n"
        << "\n"
        << "Tier-1 Plain IBLT F2 failed-only sweep. Defaults match the roadmap smoke scan.\n"
        << "\n"
        << "Options:\n"
        << "  --trials N                 trials per parameter point (default 200)\n"
        << "  --threads N                worker threads (default hardware concurrency)\n"
        << "  --seed N                   base seed (default 2026)\n"
        << "  --max-retries N            decode shuffle retries (default 30)\n"
        << "  --M-list csv               default 256,1024,4096\n"
        << "  --k-list csv               default 3\n"
        << "  --d-over-m-list csv        default 0.5,0.7,0.8,0.9,1.0,1.2,1.5,2.0,5.0,10.0\n"
        << "  --neg-ratios csv           default 0.5,0.1,0.9\n"
        << "  --gamma-list csv           optional; emits one row per gamma and reuses Round 1\n"
        << "  --out path                 write CSV to file instead of stdout\n";
}

Options parse_options(int argc, char** argv) {
    Options opt;
    if (has_arg(argc, argv, "--help") || has_arg(argc, argv, "-h")) {
        print_usage(argv[0]);
        std::exit(0);
    }

    opt.trials = arg_int(argc, argv, "--trials", opt.trials);
    opt.threads = arg_int(argc, argv, "--threads", opt.threads);
    opt.seed0 = arg_int(argc, argv, "--seed", opt.seed0);
    opt.max_retries = arg_int(argc, argv, "--max-retries", opt.max_retries);
    opt.out_path = arg_string(argc, argv, "--out", "");

    const std::string m_list = arg_string(argc, argv, "--M-list", "");
    const std::string k_list = arg_string(argc, argv, "--k-list", "");
    const std::string d_over_m_list = arg_string(argc, argv, "--d-over-m-list", "");
    const std::string neg_ratios = arg_string(argc, argv, "--neg-ratios", "");
    const std::string gamma_list = arg_string(argc, argv, "--gamma-list", "");

    if (!m_list.empty()) {
        opt.m_list = parse_int_list(m_list);
    }
    if (!k_list.empty()) {
        opt.k_list = parse_int_list(k_list);
    }
    if (!d_over_m_list.empty()) {
        opt.d_over_m_list = parse_double_list(d_over_m_list);
    }
    if (!neg_ratios.empty()) {
        opt.neg_ratios = parse_double_list(neg_ratios);
    }
    if (!gamma_list.empty()) {
        opt.gamma_list = parse_double_list(gamma_list);
    }

    opt.trials = std::max(1, opt.trials);
    opt.threads = std::max(1, opt.threads);
    opt.max_retries = std::max(0, opt.max_retries);
    if (opt.m_list.empty() || opt.k_list.empty() || opt.d_over_m_list.empty() || opt.neg_ratios.empty()) {
        throw std::invalid_argument("parameter lists must not be empty");
    }
    for (double gamma : opt.gamma_list) {
        if (gamma <= 0.0) {
            throw std::invalid_argument("gamma values must be positive");
        }
    }
    return opt;
}

std::vector<Point> make_points(const Options& opt) {
    std::vector<Point> points;
    int point_id = 0;

    for (int M : opt.m_list) {
        for (int k : opt.k_list) {
            if (M < k) {
                throw std::invalid_argument("all M values must be >= k");
            }
            for (double d_over_m : opt.d_over_m_list) {
                const int d = std::max(1, static_cast<int>(std::llround(d_over_m * static_cast<double>(M))));
                for (double neg_ratio : opt.neg_ratios) {
                    if (neg_ratio < 0.0 || neg_ratio > 1.0) {
                        throw std::invalid_argument("neg_ratio values must be in [0, 1]");
                    }
                    points.push_back(Point{point_id++, M, k, d, d_over_m, neg_ratio});
                }
            }
        }
    }

    return points;
}

}  // namespace

int main(int argc, char** argv) {
    try {
        const Options opt = parse_options(argc, argv);
        const std::vector<Point> points = make_points(opt);
        const int64_t total_tasks = static_cast<int64_t>(points.size()) * static_cast<int64_t>(opt.trials);

        std::ofstream file_out;
        std::ostream* out = &std::cout;
        if (!opt.out_path.empty()) {
            file_out.open(opt.out_path);
            if (!file_out) {
                throw std::runtime_error("failed to open output file: " + opt.out_path);
            }
            out = &file_out;
        }

        *out << "point_id,trial,M,k,d,d_over_m,neg_ratio,gamma,seed,ok1,recovered1,"
             << "S_net,S_sq,E_global,C_theory,d_hat,dhat_over_d,M2,ok2,error\n";

        std::atomic<int64_t> next_task{0};
        std::mutex out_mu;
        std::vector<std::thread> workers;
        workers.reserve(static_cast<size_t>(opt.threads));

        for (int worker = 0; worker < opt.threads; ++worker) {
            workers.emplace_back([&]() {
                while (true) {
                    const int64_t task = next_task.fetch_add(1);
                    if (task >= total_tasks) {
                        break;
                    }

                    const int point_idx = static_cast<int>(task / opt.trials);
                    const int trial = static_cast<int>(task % opt.trials);
                    const Point& point = points[static_cast<size_t>(point_idx)];
                    const int seed = opt.seed0 + point.point_id * 1000003 + trial;
                    const auto rows = make_rows(point, trial, seed, opt.max_retries, opt.gamma_list);

                    std::lock_guard<std::mutex> lock(out_mu);
                    for (const auto& row : rows) {
                        *out << row << '\n';
                    }
                }
            });
        }

        for (auto& worker : workers) {
            worker.join();
        }
    } catch (const std::exception& ex) {
        std::cerr << "error: " << ex.what() << '\n';
        return 1;
    }

    return 0;
}
