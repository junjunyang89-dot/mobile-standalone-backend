package com.mobile.standalone.service;

import com.mobile.standalone.entity.UiFlow;
import com.mobile.standalone.mapper.UiFlowMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UiFlowService {

    private final UiFlowMapper uiFlowMapper;

    public List<UiFlow> list(String appId, String platform, String catalog, Boolean componentOnly) {
        return uiFlowMapper.findByFilters(blankToNull(appId), blankToNull(platform), blankToNull(catalog), componentOnly);
    }

    public UiFlow get(String id) {
        return uiFlowMapper.findById(id);
    }

    @Transactional
    public UiFlow create(UiFlow flow) {
        if (flow.getName() == null || flow.getName().isBlank()) {
            throw new IllegalArgumentException("流程名称不能为空");
        }
        if (flow.getSteps() == null || flow.getSteps().isBlank()) {
            flow.setSteps("[]");
        }
        if (flow.getCatalog() == null || flow.getCatalog().isBlank()) {
            flow.setCatalog("SHARED");
        }
        if (flow.getIsComponent() == null) flow.setIsComponent(false);
        if (flow.getEnabled() == null) flow.setEnabled(true);
        flow.setId("uif-" + UUID.randomUUID());
        flow.setName(flow.getName().trim());
        LocalDateTime now = LocalDateTime.now();
        flow.setCreatedAt(now);
        flow.setUpdatedAt(now);
        uiFlowMapper.insert(flow);
        return flow;
    }

    @Transactional
    public UiFlow update(String id, UiFlow patch) {
        UiFlow existing = uiFlowMapper.findById(id);
        if (existing == null) return null;
        if (patch.getName() != null) existing.setName(patch.getName().trim());
        if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
        if (patch.getAppId() != null) existing.setAppId(patch.getAppId());
        if (patch.getPlatform() != null) existing.setPlatform(patch.getPlatform());
        if (patch.getCatalog() != null) existing.setCatalog(patch.getCatalog());
        if (patch.getIsComponent() != null) existing.setIsComponent(patch.getIsComponent());
        if (patch.getSteps() != null) existing.setSteps(patch.getSteps());
        if (patch.getEnabled() != null) existing.setEnabled(patch.getEnabled());
        existing.setUpdatedAt(LocalDateTime.now());
        uiFlowMapper.update(existing);
        return existing;
    }

    @Transactional
    public boolean delete(String id) {
        return uiFlowMapper.deleteById(id) > 0;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
