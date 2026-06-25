package mx.ipn.escom.tesoreria.tests;

import java.util.List;

import mx.ipn.escom.tesoreria.security.Tokens;
import mx.ipn.escom.tesoreria.tests.RunTests.Case;

/**
 * Suite pinning the cross-node JWT property the cluster relies on: because the
 * three nodes are constructed with the SAME {@code TES_JWT_SECRET}, a token minted
 * on any node validates on every other node, while a token signed with a different
 * secret is rejected. This is what lets a client authenticate once (on the leader)
 * and have its Bearer token honoured anywhere in the cluster.
 *
 * <p>The feature works today, but nothing guarded it: a future change to
 * {@link Tokens} (issuer, algorithm, claims) could silently break cross-node
 * validation without any test noticing. These deterministic, in-process checks
 * close that regression gap.
 */
public final class JwtCrossNodeTest {

    /**
     * Builds the list of cases contributed by this suite.
     *
     * @return ordered list of named cases for {@link RunTests}
     */
    public List<Case> cases() {
        return List.of(
                new Case("token issued on one node validates on another (same secret)",
                        this::sameSecretValidatesEverywhere),
                new Case("token signed with a different secret is rejected",
                        this::differentSecretRejected));
    }

    /** Two nodes share the secret: a token from node A carries its subject on node B. */
    private void sameSecretValidatesEverywhere() {
        Tokens nodeA = new Tokens("cluster-shared-secret");
        Tokens nodeB = new Tokens("cluster-shared-secret");
        String token = nodeA.issue("juan");
        Assert.equals("subject survives cross-node validation", "juan", nodeB.validate(token));
    }

    /** A token signed with a foreign secret must not validate on a node. */
    private void differentSecretRejected() {
        Tokens issuer = new Tokens("cluster-shared-secret");
        Tokens foreign = new Tokens("a-different-secret");
        String token = issuer.issue("juan");
        Assert.throwsException("token signed with a foreign secret must be rejected",
                () -> foreign.validate(token));
    }
}
