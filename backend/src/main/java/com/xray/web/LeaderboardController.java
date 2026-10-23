package com.xray.web;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.xray.leaderboard.LeaderboardService;
import com.xray.leaderboard.LeaderboardService.Board;
import com.xray.leaderboard.LeaderboardService.OrderBy;
import com.xray.leaderboard.LeaderboardService.Period;

@RestController
@RequestMapping("/api/leaderboard")
public class LeaderboardController {

	private final LeaderboardService leaderboard;

	public LeaderboardController(LeaderboardService leaderboard) {
		this.leaderboard = leaderboard;
	}

	@GetMapping
	public Board board(@RequestParam(defaultValue = "WEEK") Period period,
			@RequestParam(defaultValue = "OVERALL") String category,
			@RequestParam(defaultValue = "PNL") OrderBy orderBy) {
		String c = category.toUpperCase(Locale.ROOT);
		if (!LeaderboardService.CATEGORIES.contains(c)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown category " + category);
		}
		return leaderboard.board(period, c, orderBy);
	}
}
