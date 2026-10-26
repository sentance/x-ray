package com.xray.web;

import java.util.*;
import org.springframework.web.bind.annotation.*;
import com.xray.paper.*;

/** Session UUIDs are unguessable capabilities, retained by the creating browser. No public session list. */
@RestController
@RequestMapping("/api/paper")
public class PaperController {
    private final PaperService service;
    public PaperController(PaperService service) { this.service = service; }
    @GetMapping("/defaults") public PaperModel.Settings defaults() { return PaperModel.Settings.defaults(); }
    @PostMapping(consumes = "application/json") public PaperService.View create(@RequestBody PaperModel.Settings settings) {
        return service.create(settings);
    }
    @GetMapping("/{id}") public PaperService.View view(@PathVariable UUID id) { return service.view(id); }
    @PostMapping("/{id}/resume") public PaperService.View resume(@PathVariable UUID id) { return service.start(id); }
    @PostMapping("/{id}/pause") public PaperService.View pause(@PathVariable UUID id) { return service.pause(id); }
    @GetMapping("/{id}/evidence") public List<PaperRepository.Row> evidence(@PathVariable UUID id, @RequestParam(defaultValue = "0") long after) {
        return service.evidence(id, after);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(IllegalArgumentException e) { return Map.of("message", e.getMessage()); }
}
