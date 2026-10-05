package com.github.feng111627.privacybudgetqueryjava.web;

import com.github.feng111627.privacybudgetqueryjava.model.BatchSnapshot;
import com.github.feng111627.privacybudgetqueryjava.model.QueryRecord;
import com.github.feng111627.privacybudgetqueryjava.model.QueryRequest;
import com.github.feng111627.privacybudgetqueryjava.model.QueryResponse;
import com.github.feng111627.privacybudgetqueryjava.service.PrivacyQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 隐私统计查询 HTTP 接口。 */
@RestController
@RequestMapping("/api/privacy")
public class PrivacyQueryController {

    private final PrivacyQueryService service;

    public PrivacyQueryController(PrivacyQueryService service) {
        this.service = service;
    }

    @PostMapping("/queries")
    public QueryResponse submit(@RequestBody QueryRequest request) {
        return service.submit(request);
    }

    @GetMapping("/callers/{callerId}/history")
    public List<QueryRecord> history(@PathVariable String callerId) {
        return service.history(callerId);
    }

    @GetMapping("/callers/{callerId}/batches/{batchId}")
    public BatchSnapshot snapshot(@PathVariable String callerId, @PathVariable String batchId) {
        return service.snapshot(callerId, batchId);
    }
}
