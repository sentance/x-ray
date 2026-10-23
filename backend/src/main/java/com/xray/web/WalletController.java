package com.xray.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.xray.ingest.SyncJobRepository;
import com.xray.ingest.SyncJobRepository.SyncJob;
import com.xray.ingest.WalletRepository;
import com.xray.ingest.WalletRepository.Wallet;
import com.xray.ingest.WalletSyncService;

@RestController
@RequestMapping("/api/wallets")
public class WalletController {

	public record SyncStatus(Wallet wallet, SyncJob job) {
	}

	private final AddressResolver resolver;

	private final WalletSyncService sync;

	private final WalletRepository wallets;

	private final SyncJobRepository jobs;

	public WalletController(AddressResolver resolver, WalletSyncService sync, WalletRepository wallets,
			SyncJobRepository jobs) {
		this.resolver = resolver;
		this.sync = sync;
		this.wallets = wallets;
		this.jobs = jobs;
	}

	@GetMapping("/resolve")
	public Map<String, String> resolve(@RequestParam String q) {
		return Map.of("address", resolver.resolve(q));
	}

	@GetMapping("/recent")
	public List<Wallet> recent() {
		return wallets.recentlySynced(20);
	}

	@PostMapping("/{address}/sync")
	public SyncStatus startSync(@PathVariable String address) {
		String a = AddressResolver.requireAddress(address);
		SyncJob job = sync.start(a);
		return new SyncStatus(wallets.find(a).orElseThrow(), job);
	}

	@GetMapping("/{address}/sync")
	public SyncStatus syncStatus(@PathVariable String address) {
		String a = AddressResolver.requireAddress(address);
		Wallet wallet = wallets.find(a)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet not analyzed yet"));
		return new SyncStatus(wallet, jobs.latest(a).orElse(null));
	}
}
