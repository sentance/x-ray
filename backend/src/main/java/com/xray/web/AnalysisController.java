package com.xray.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.xray.analysis.AnalysisService;
import com.xray.analysis.AnalysisService.Overview;
import com.xray.analysis.AnalysisService.StrategyReport;
import com.xray.analysis.DecayAnalyzer.DecayReport;
import com.xray.analysis.PnlAnalyzer.PnlReport;
import com.xray.analysis.SkillAnalyzer.SkillReport;
import com.xray.binance.BinanceLagService;
import com.xray.binance.BinanceLagService.Status;

@RestController
@RequestMapping("/api/wallets/{address}")
public class AnalysisController {

	private final AnalysisService analysis;

	private final BinanceLagService binanceLag;

	public AnalysisController(AnalysisService analysis, BinanceLagService binanceLag) {
		this.analysis = analysis;
		this.binanceLag = binanceLag;
	}

	@GetMapping("/overview")
	public Overview overview(@PathVariable String address) {
		return analysis.overview(AddressResolver.requireAddress(address));
	}

	@GetMapping("/pnl")
	public PnlReport pnl(@PathVariable String address) {
		return analysis.pnl(AddressResolver.requireAddress(address));
	}

	@GetMapping("/strategy")
	public StrategyReport strategy(@PathVariable String address) {
		return analysis.strategy(AddressResolver.requireAddress(address));
	}

	@GetMapping("/skill")
	public SkillReport skill(@PathVariable String address) {
		return analysis.skill(AddressResolver.requireAddress(address));
	}

	@GetMapping("/decay")
	public DecayReport decay(@PathVariable String address) {
		return analysis.decay(AddressResolver.requireAddress(address));
	}

	@GetMapping("/binance")
	public Status binance(@PathVariable String address) {
		return binanceLag.status(AddressResolver.requireAddress(address));
	}

	@PostMapping("/binance")
	public Status startBinance(@PathVariable String address) {
		return binanceLag.start(AddressResolver.requireAddress(address));
	}
}
