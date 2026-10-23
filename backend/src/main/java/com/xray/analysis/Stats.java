package com.xray.analysis;

import java.util.List;

/** Small statistics helpers; all probabilities are exact or standard approximations, no external library. */
public final class Stats {

	private Stats() {
	}

	/** P(X >= k) for X ~ Binomial(n, 1/2). */
	public static double binomialUpperTail(int n, int k) {
		if (k <= 0) {
			return 1;
		}
		if (k > n) {
			return 0;
		}
		double[] logFactorial = logFactorials(n);
		double total = 0;
		for (int i = k; i <= n; i++) {
			total += Math.exp(logFactorial[n] - logFactorial[i] - logFactorial[n - i] - n * Math.log(2));
		}
		return Math.min(1, total);
	}

	/**
	 * Probability that {@code n} fair coin flips contain a run of at least {@code run} heads — the chance a
	 * coin-flipper matches a winning streak this long.
	 */
	public static double longestRunAtLeast(int n, int run) {
		if (run <= 0) {
			return 1;
		}
		if (run > n) {
			return 0;
		}
		// state[j] = P(no run >= `run` so far and the current run of heads has length j)
		double[] state = new double[run];
		state[0] = 1;
		for (int i = 0; i < n; i++) {
			double[] next = new double[run];
			double alive = 0;
			for (int j = 0; j < run; j++) {
				alive += state[j];
				if (j + 1 < run) {
					next[j + 1] += state[j] * 0.5;
				}
			}
			next[0] = alive * 0.5;
			state = next;
		}
		double noRun = 0;
		for (double p : state) {
			noRun += p;
		}
		return Math.max(0, 1 - noRun);
	}

	/** One-sided p-value P(Z >= z) for a standard normal. */
	public static double normalUpperTail(double z) {
		return 0.5 * erfc(z / Math.sqrt(2));
	}

	public static double mean(List<Double> xs) {
		return xs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
	}

	public static double stdDev(List<Double> xs) {
		if (xs.size() < 2) {
			return 0;
		}
		double m = mean(xs);
		double ss = xs.stream().mapToDouble(x -> (x - m) * (x - m)).sum();
		return Math.sqrt(ss / (xs.size() - 1));
	}

	public static double safeDiv(double a, double b) {
		return b == 0 ? 0 : a / b;
	}

	private static double[] logFactorials(int n) {
		double[] f = new double[n + 1];
		for (int i = 1; i <= n; i++) {
			f[i] = f[i - 1] + Math.log(i);
		}
		return f;
	}

	// Numerical Recipes erfc approximation, relative error < 1.2e-7.
	private static double erfc(double x) {
		double z = Math.abs(x);
		double t = 1 / (1 + 0.5 * z);
		double r = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418
				+ t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587
						+ t * (-0.82215223 + t * 0.17087277)))))))));
		return x >= 0 ? r : 2 - r;
	}
}
