package com.inboxiq.auth;

import com.inboxiq.auth.entity.AuthSession;
import com.inboxiq.auth.repository.AuthSessionRepository;
import com.inboxiq.auth.service.RefreshTokenService;
import com.inboxiq.user.entity.User;
import com.inboxiq.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("dev")
class RefreshTokenConcurrencyIntegrationTest {

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private AuthSessionRepository authSessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("Concurrency: Simultaneous refresh requests on PostgreSQL cannot create multiple active descendants")
    void testConcurrentRefreshRequestsPreventFamilyFork() throws Exception {
        User user = new User("concurrent@inboxiq.local", "hash", "Concurrent User", null);
        user = userRepository.save(user);

        var sessionResult = refreshTokenService.createSession(user.getId(), "127.0.0.1", "JUnit5");
        String rawToken = sessionResult.rawToken();
        UUID familyId = sessionResult.session().getTokenFamilyId();

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<RefreshTokenService.RotationResult> successes = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int threadIdx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    var result = refreshTokenService.rotateSession(rawToken, "10.0.0." + threadIdx, "Agent-" + threadIdx);
                    successes.add(result);
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release all threads simultaneously
        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();

        // Exactly one thread should successfully rotate, the concurrent thread must fail
        assertThat(successes).hasSize(1);
        assertThat(failures).hasSize(1);

        // Verify database state: exactly ONE active session exists in this family, no duplicate descendants
        List<AuthSession> familySessions = authSessionRepository.findAllByTokenFamilyId(familyId);
        long activeCount = familySessions.stream().filter(AuthSession::isActive).count();

        // If the losing thread detected reuse, activeCount may be 0 (full revocation) or 1 (the winning child session).
        // In either case, activeCount MUST NEVER be greater than 1!
        assertThat(activeCount).isLessThanOrEqualTo(1L);

        // Clean up
        authSessionRepository.deleteAll(familySessions);
        userRepository.delete(user);
    }
}

