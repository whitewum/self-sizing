package main

import (
	"encoding/csv"
	"math"
	"os"
	"path/filepath"
	"sort"
	"strconv"
)

type summaryKey struct {
	D, Plus, Minus, M int
}

type ratioGroup struct {
	correct, meanDegree, regular []float64
	irregularDecoded             int
	regularDecoded               int
}

type metricSet struct {
	mean, median, p05, p95, rmse, within20 float64
}

func writeReports(cfg config, observations []observation) error {
	if err := os.MkdirAll(cfg.OutDir, 0o755); err != nil {
		return err
	}
	if err := writeDistribution(filepath.Join(cfg.OutDir, "distribution.csv")); err != nil {
		return err
	}
	if err := writeObservations(filepath.Join(cfg.OutDir, "observations.csv"), observations); err != nil {
		return err
	}
	if err := writeEstimatorSummary(filepath.Join(cfg.OutDir, "estimator_summary.csv"), observations); err != nil {
		return err
	}
	return writeDecodeSummary(filepath.Join(cfg.OutDir, "decode_summary.csv"), observations)
}

func openCSV(path string, header []string) (*os.File, *csv.Writer, error) {
	file, err := os.Create(path)
	if err != nil {
		return nil, nil, err
	}
	writer := csv.NewWriter(file)
	if err := writer.Write(header); err != nil {
		file.Close()
		return nil, nil, err
	}
	return file, writer, nil
}

func closeCSV(file *os.File, writer *csv.Writer) error {
	writer.Flush()
	if err := writer.Error(); err != nil {
		file.Close()
		return err
	}
	return file.Close()
}

func writeDistribution(path string) error {
	file, writer, err := openCSV(path, []string{"distribution", "degree", "probability", "mean_degree", "second_moment", "variance"})
	if err != nil {
		return err
	}
	for _, term := range optimizedIrregular.Terms {
		row := []string{optimizedIrregular.Name, strconv.Itoa(term.Degree), f64(term.Probability), f64(optimizedIrregular.mean()), f64(optimizedIrregular.secondMoment()), f64(optimizedIrregular.variance())}
		if err := writer.Write(row); err != nil {
			file.Close()
			return err
		}
	}
	return closeCSV(file, writer)
}

func writeObservations(path string, observations []observation) error {
	header := []string{
		"case_id", "trial", "d", "plus", "minus", "plus_fraction", "m", "degree_2_count", "degree_3_count", "degree_18_count",
		"irregular_energy", "gamma_correct", "gamma_mean_degree", "d_hat_correct", "ratio_correct", "d_hat_mean_degree", "ratio_mean_degree",
		"regular_energy", "gamma_regular", "d_hat_regular", "ratio_regular", "irregular_decoded", "regular_decoded",
	}
	file, writer, err := openCSV(path, header)
	if err != nil {
		return err
	}
	for _, o := range observations {
		row := []string{
			strconv.Itoa(o.CaseID), strconv.Itoa(o.Trial), strconv.Itoa(o.D), strconv.Itoa(o.Plus), strconv.Itoa(o.Minus), f64(o.PlusFraction), strconv.Itoa(o.M),
			strconv.Itoa(o.Degree2), strconv.Itoa(o.Degree3), strconv.Itoa(o.Degree18),
			f64(o.IrregularEnergy), f64(o.GammaCorrect), f64(o.GammaMeanDegree), f64(o.DHatCorrect), f64(o.RatioCorrect), f64(o.DHatMeanDegree), f64(o.RatioMeanDegree),
			f64(o.RegularEnergy), f64(o.GammaRegular), f64(o.DHatRegular), f64(o.RatioRegular), strconv.FormatBool(o.IrregularDecoded), strconv.FormatBool(o.RegularDecoded),
		}
		if err := writer.Write(row); err != nil {
			file.Close()
			return err
		}
	}
	return closeCSV(file, writer)
}

func grouped(observations []observation) (map[summaryKey]*ratioGroup, []summaryKey) {
	groups := make(map[summaryKey]*ratioGroup)
	for _, o := range observations {
		key := summaryKey{D: o.D, Plus: o.Plus, Minus: o.Minus, M: o.M}
		group := groups[key]
		if group == nil {
			group = &ratioGroup{}
			groups[key] = group
		}
		group.correct = append(group.correct, o.RatioCorrect)
		group.meanDegree = append(group.meanDegree, o.RatioMeanDegree)
		group.regular = append(group.regular, o.RatioRegular)
		if o.IrregularDecoded {
			group.irregularDecoded++
		}
		if o.RegularDecoded {
			group.regularDecoded++
		}
	}
	keys := make([]summaryKey, 0, len(groups))
	for key := range groups {
		keys = append(keys, key)
	}
	sort.Slice(keys, func(i, j int) bool {
		if keys[i].D != keys[j].D {
			return keys[i].D < keys[j].D
		}
		if keys[i].Plus != keys[j].Plus {
			return keys[i].Plus < keys[j].Plus
		}
		return keys[i].M < keys[j].M
	})
	return groups, keys
}

func writeEstimatorSummary(path string, observations []observation) error {
	groups, keys := grouped(observations)
	header := []string{
		"d", "plus", "minus", "plus_fraction", "m", "trials", "expected_mean_degree_ratio",
		"mean_correct", "median_correct", "p05_correct", "p95_correct", "rmse_correct", "within20_correct",
		"mean_mean_degree", "median_mean_degree", "p05_mean_degree", "p95_mean_degree", "rmse_mean_degree", "within20_mean_degree",
		"mean_regular", "median_regular", "p05_regular", "p95_regular", "rmse_regular", "within20_regular",
	}
	file, writer, err := openCSV(path, header)
	if err != nil {
		return err
	}
	for _, key := range keys {
		group := groups[key]
		correct := summarizeRatios(group.correct)
		meanDegree := summarizeRatios(group.meanDegree)
		regular := summarizeRatios(group.regular)
		row := []string{
			strconv.Itoa(key.D), strconv.Itoa(key.Plus), strconv.Itoa(key.Minus), f64(float64(key.Plus) / float64(key.D)), strconv.Itoa(key.M), strconv.Itoa(len(group.correct)),
			f64(optimizedIrregular.gamma(key.M) / optimizedIrregular.meanDegreeGamma(key.M)),
			f64(correct.mean), f64(correct.median), f64(correct.p05), f64(correct.p95), f64(correct.rmse), f64(correct.within20),
			f64(meanDegree.mean), f64(meanDegree.median), f64(meanDegree.p05), f64(meanDegree.p95), f64(meanDegree.rmse), f64(meanDegree.within20),
			f64(regular.mean), f64(regular.median), f64(regular.p05), f64(regular.p95), f64(regular.rmse), f64(regular.within20),
		}
		if err := writer.Write(row); err != nil {
			file.Close()
			return err
		}
	}
	return closeCSV(file, writer)
}

func writeDecodeSummary(path string, observations []observation) error {
	groups, keys := grouped(observations)
	file, writer, err := openCSV(path, []string{"d", "plus", "minus", "plus_fraction", "m", "load_d_over_m", "trials", "irregular_success_rate", "regular_k3_success_rate"})
	if err != nil {
		return err
	}
	for _, key := range keys {
		group := groups[key]
		trials := len(group.correct)
		row := []string{
			strconv.Itoa(key.D), strconv.Itoa(key.Plus), strconv.Itoa(key.Minus), f64(float64(key.Plus) / float64(key.D)), strconv.Itoa(key.M), f64(float64(key.D) / float64(key.M)), strconv.Itoa(trials),
			f64(float64(group.irregularDecoded) / float64(trials)), f64(float64(group.regularDecoded) / float64(trials)),
		}
		if err := writer.Write(row); err != nil {
			file.Close()
			return err
		}
	}
	return closeCSV(file, writer)
}

func summarizeRatios(values []float64) metricSet {
	values = append([]float64(nil), values...)
	sort.Float64s(values)
	squares := 0.0
	within := 0
	for _, value := range values {
		squares += (value - 1) * (value - 1)
		if math.Abs(value-1) <= 0.2 {
			within++
		}
	}
	return metricSet{
		mean: average(values), median: quantileSorted(values, 0.5), p05: quantileSorted(values, 0.05), p95: quantileSorted(values, 0.95),
		rmse: math.Sqrt(squares / float64(len(values))), within20: float64(within) / float64(len(values)),
	}
}

func average(values []float64) float64 {
	sum := 0.0
	for _, value := range values {
		sum += value
	}
	return sum / float64(len(values))
}

func quantileSorted(values []float64, q float64) float64 {
	if len(values) == 1 {
		return values[0]
	}
	position := q * float64(len(values)-1)
	low, high := int(math.Floor(position)), int(math.Ceil(position))
	if low == high {
		return values[low]
	}
	fraction := position - float64(low)
	return values[low]*(1-fraction) + values[high]*fraction
}

func f64(value float64) string {
	return strconv.FormatFloat(value, 'g', 10, 64)
}
