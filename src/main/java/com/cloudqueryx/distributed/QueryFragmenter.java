package com.cloudqueryx.distributed;

import com.cloudqueryx.planner.physical.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class QueryFragmenter {

    private static final Logger log = LoggerFactory.getLogger(QueryFragmenter.class);

    public List<QueryFragment> fragment(PhysicalPlan plan, int workerCount) {
        log.debug("Fragmenting plan across {} workers", workerCount);

        List<QueryFragment> fragments = new ArrayList<>();
        fragmentRecursive(plan, fragments, workerCount, null);

        if (fragments.isEmpty()) {
            fragments.add(new QueryFragment(
                    UUID.randomUUID().toString(),
                    plan,
                    0,
                    QueryFragment.FragmentType.ROOT
            ));
        }

        log.info("Created {} query fragments", fragments.size());
        return fragments;
    }

    private void fragmentRecursive(PhysicalPlan plan, List<QueryFragment> fragments,
                                    int workerCount, String parentFragmentId) {
        if (plan instanceof Exchange exchange) {
            String fragmentId = UUID.randomUUID().toString();

            for (int partition = 0; partition < Math.min(workerCount, exchange.partitionCount()); partition++) {
                fragments.add(new QueryFragment(
                        fragmentId + "-" + partition,
                        exchange.input(),
                        partition,
                        QueryFragment.FragmentType.LEAF
                ));
            }

            return;
        }

        if (plan instanceof HashJoin hashJoin) {
            String fragmentId = UUID.randomUUID().toString();

            fragments.add(new QueryFragment(
                    fragmentId,
                    plan,
                    0,
                    parentFragmentId == null ? QueryFragment.FragmentType.ROOT : QueryFragment.FragmentType.INTERMEDIATE
            ));

            return;
        }

        if (plan instanceof TableScan) {
            for (int partition = 0; partition < workerCount; partition++) {
                fragments.add(new QueryFragment(
                        UUID.randomUUID().toString(),
                        plan,
                        partition,
                        QueryFragment.FragmentType.LEAF
                ));
            }
            return;
        }

        String fragmentId = UUID.randomUUID().toString();
        fragments.add(new QueryFragment(
                fragmentId,
                plan,
                0,
                parentFragmentId == null ? QueryFragment.FragmentType.ROOT : QueryFragment.FragmentType.INTERMEDIATE
        ));
    }

    public record QueryFragment(
            String fragmentId,
            PhysicalPlan plan,
            int partitionId,
            FragmentType type
    ) {
        public enum FragmentType {
            ROOT,
            INTERMEDIATE,
            LEAF
        }

        @Override
        public String toString() {
            return String.format("Fragment[%s, type=%s, partition=%d]",
                    fragmentId.substring(0, 8), type, partitionId);
        }
    }
}
