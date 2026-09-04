package main

import (
	"bufio"
	"fmt"
	"os"
	"strconv"
	"strings"

	"self-sizing-artifact/rateless-vs-self-sizing/internal/plain"
)

func main() {
	scanner := bufio.NewScanner(os.Stdin)
	writer := bufio.NewWriter(os.Stdout)
	defer writer.Flush()
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) != 3 {
			fatalf("expected: fp m seed; got %q", line)
		}
		fp, err := strconv.ParseUint(fields[0], 10, 64)
		if err != nil {
			fatalf("fp: %v", err)
		}
		m, err := strconv.Atoi(fields[1])
		if err != nil {
			fatalf("m: %v", err)
		}
		seed, err := strconv.ParseUint(fields[2], 10, 64)
		if err != nil {
			fatalf("seed: %v", err)
		}
		positions := plain.Positions(fp, m, seed)
		fmt.Fprintf(writer, "%d %d %d\n", positions[0], positions[1], positions[2])
	}
	if err := scanner.Err(); err != nil {
		fatalf("read: %v", err)
	}
}

func fatalf(format string, args ...any) {
	fmt.Fprintf(os.Stderr, format+"\n", args...)
	os.Exit(1)
}
