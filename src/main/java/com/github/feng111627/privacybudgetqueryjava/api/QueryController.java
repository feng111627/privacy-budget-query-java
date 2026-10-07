package com.github.feng111627.privacybudgetqueryjava.api;

import com.github.feng111627.privacybudgetqueryjava.domain.QueryRequest;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryResult;
import com.github.feng111627.privacybudgetqueryjava.domain.ReplayResult;
import com.github.feng111627.privacybudgetqueryjava.query.QueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统计查询与历史结论复现入口。
 */
@RestController
@RequestMapping("/api")
public class QueryController {

    private final QueryService queryService;

    public QueryController(QueryService queryService) {
        this.queryService = queryService;
    }

    /** 统计查询：完整落在某一个确定的参数/授权版本上。 */
    @PostMapping("/query")
    public QueryResult query(@RequestBody QueryRequest request) {
        return queryService.execute(request);
    }

    /** 按审计序号取回历史结论，并用当时版本参数重算，验证复现一致性。 */
    @GetMapping("/replay/{auditSeq}")
    public ReplayResult replay(@PathVariable long auditSeq) {
        return queryService.replay(auditSeq);
    }
}
