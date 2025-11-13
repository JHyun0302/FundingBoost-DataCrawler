package kcs.funding.crawler.service;


import java.time.LocalDateTime;
import kcs.funding.crawler.repository.ItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ItemService {

    private final ItemRepository itemRepository;

    @Transactional
    public int purgeOldItems(int retentionDays) {
        LocalDateTime threshold = LocalDateTime.now().minusDays(retentionDays);
        int deleted = itemRepository.deleteByModifiedDateBefore(threshold);
        log.info("purgeOldImtes: retentionDays={}, threshold={}, deleted={}", retentionDays, threshold, deleted);
        return deleted;
    }
}
