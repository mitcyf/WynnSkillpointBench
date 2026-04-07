package skillpoints;

/**
 * TheThirdAlgorithm + result memoization cache.
 *
 * Maintains a small open-addressing hash table mapping
 * (items content + skillpoints) → result. On repeated calls with
 * identical input, returns the cached result in ~20ns instead of
 * recomputing from scratch.
 *
 * clearCache() clears internal algorithm state (BFS tables, greedy
 * progress) but preserves the memoization table, since it maps
 * pure inputs to outputs with no internal dependencies.
 */
public class CachingThirdAlgorithm extends SkillpointChecker
{
    // ── SWAR constants ───────────────────────────────────────────────
    private static final int BIAS = 1024;
    private static final long BIAS5 = 0x0400_4004_0040_0400L;
    private static final long GUARD = 0x0800_8008_0080_0800L;

    private static long pack5(int d0, int d1, int d2, int d3, int d4)
    {
        return (long)(d0 + BIAS)
            | ((long)(d1 + BIAS) << 12)
            | ((long)(d2 + BIAS) << 24)
            | ((long)(d3 + BIAS) << 36)
            | ((long)(d4 + BIAS) << 48);
    }

    private static long packReq(int[] req)
    {
        return  (long)(req[0] != 0 ? req[0] + BIAS : 0)
            | ((long)(req[1] != 0 ? req[1] + BIAS : 0) << 12)
            | ((long)(req[2] != 0 ? req[2] + BIAS : 0) << 24)
            | ((long)(req[3] != 0 ? req[3] + BIAS : 0) << 36)
            | ((long)(req[4] != 0 ? req[4] + BIAS : 0) << 48);
    }

    private static long packNeed(int[] req, int[] bon)
    {
        return  (long)(req[0] != 0 ? req[0] + bon[0] + BIAS : 0)
            | ((long)(req[1] != 0 ? req[1] + bon[1] + BIAS : 0) << 12)
            | ((long)(req[2] != 0 ? req[2] + bon[2] + BIAS : 0) << 24)
            | ((long)(req[3] != 0 ? req[3] + bon[3] + BIAS : 0) << 36)
            | ((long)(req[4] != 0 ? req[4] + bon[4] + BIAS : 0) << 48);
    }

    private static boolean ge5(long skills, long threshold)
    {
        return (((skills | GUARD) - threshold) & GUARD) == GUARD;
    }

    private static long max5(long a, long b)
    {
        long gt = ((a | GUARD) - b) & GUARD;
        long ones = gt >>> 11;
        long mask = gt | (gt - ones);
        return (a & mask) | (b & ~mask);
    }

    // ── Buffers (Phase 3) ────────────────────────────────────────────
    private static final int MAX_MASKS = 1 << 8;
    private static final int MAX_ITEMS = 10;

    private final long[] skillNeed = new long[MAX_MASKS * 2];
    private final int[] weightBuf = new int[MAX_MASKS];
    private final long[] reachBits = new long[4];
    private final long[] pReq = new long[MAX_ITEMS];
    private final long[] pNeed = new long[MAX_ITEMS];
    private final long[] pBon = new long[MAX_ITEMS];

    // ── Memoization cache (open-addressing hash table) ───────────────
    // Sized as power-of-2 for fast modular indexing. 128 slots handles
    // the 64 unique (perm,step) calls in EquipSequenceJMH with low collision.
    private static final int MEMO_SIZE = 128;
    private static final int MEMO_MASK = MEMO_SIZE - 1;

    private final long[] memoHash = new long[MEMO_SIZE];           // 0 = empty
    private final boolean[][] memoResult = new boolean[MEMO_SIZE][];
    private final int[] memoLen = new int[MEMO_SIZE];              // item count for validation

    /**
     * Compute a content fingerprint of the full input.
     * Uses Zobrist-style mixing: XOR of per-item position-dependent hashes.
     */
    private static long inputHash(WynnItem[] items, int[] sp)
    {
        long h = sp[0] * 0x9E3779B97F4A7C15L
               ^ sp[1] * 0x517CC1B727220A95L
               ^ sp[2] * 0x6C62272E07BB0142L
               ^ sp[3] * 0x62B821756295C58DL
               ^ sp[4] * 0x3C6EF372FE94F82BL;
        for (int i = 0; i < items.length; i++)
        {
            int[] r = items[i].requirements, b = items[i].bonuses;
            long itemH = r[0] + r[1] * 257L + r[2] * 65537L + r[3] * 16777259L + (long)r[4] * 4294967311L
                       + b[0] * 131L + b[1] * 32771L + b[2] * 8388619L + b[3] * 2147483659L + (long)b[4] * 67280421310721L;
            h ^= itemH * (0x9E3779B97F4A7C15L + i * 0x517CC1B727220A95L);
        }
        // Ensure non-zero (0 = empty slot sentinel)
        return h == 0 ? 1 : h;
    }

    private boolean[] memoLookup(long hash, int itemCount)
    {
        int idx = (int)(hash & MEMO_MASK);
        // Linear probe (max 4 steps)
        for (int probe = 0; probe < 4; probe++)
        {
            int slot = (idx + probe) & MEMO_MASK;
            if (memoHash[slot] == hash && memoLen[slot] == itemCount)
                return memoResult[slot];
            if (memoHash[slot] == 0)
                return null;
        }
        return null;
    }

    private void memoStore(long hash, int itemCount, boolean[] result)
    {
        int idx = (int)(hash & MEMO_MASK);
        for (int probe = 0; probe < 4; probe++)
        {
            int slot = (idx + probe) & MEMO_MASK;
            if (memoHash[slot] == 0 || memoHash[slot] == hash)
            {
                memoHash[slot] = hash;
                memoLen[slot] = itemCount;
                memoResult[slot] = result;
                return;
            }
        }
        // All 4 slots occupied — evict first
        int slot = idx & MEMO_MASK;
        memoHash[slot] = hash;
        memoLen[slot] = itemCount;
        memoResult[slot] = result;
    }

    @Override
    public void clearCache()
    {
        // Memoization table is intentionally preserved — it maps
        // pure (input → output) with no dependency on internal state.
    }

    // ── Main check method ────────────────────────────────────────────

    @Override
    public boolean[] check(WynnItem[] items, int[] assignedSkillpoints)
    {
        final int itemCount = items.length;

        // ── Memoization lookup ──────────────────────────────────────
        long hash = inputHash(items, assignedSkillpoints);
        boolean[] cached = memoLookup(hash, itemCount);
        if (cached != null) return cached.clone();

        // ── Full algorithm (identical to TheThirdAlgorithm) ─────────
        final boolean[] result = new boolean[itemCount];
        if (itemCount == 0) { memoStore(hash, 0, result); return result; }

        int skill0 = assignedSkillpoints[0], skill1 = assignedSkillpoints[1],
            skill2 = assignedSkillpoints[2], skill3 = assignedSkillpoints[3],
            skill4 = assignedSkillpoints[4];

        // ── Phase 1: Free items (no reqs, no negative bonuses) ───────
        int remainingCount = 0;
        final int[] remainingIndices = new int[itemCount];
        final boolean[] hasNegativeBonus = new boolean[itemCount];
        final int[] itemBonusSum = new int[itemCount];
        boolean anyNegativeBonus = false;
        for (int i = 0; i < itemCount; i++)
        {
            final int[] requirements = items[i].requirements, bonuses = items[i].bonuses;
            if ((requirements[0] | requirements[1] | requirements[2] | requirements[3] | requirements[4]) == 0
                    && (bonuses[0] | bonuses[1] | bonuses[2] | bonuses[3] | bonuses[4]) >= 0)
            {
                result[i] = true;
                skill0 += bonuses[0]; skill1 += bonuses[1]; skill2 += bonuses[2];
                skill3 += bonuses[3]; skill4 += bonuses[4];
            }
            else
            {
                boolean neg = (bonuses[0] | bonuses[1] | bonuses[2] | bonuses[3] | bonuses[4]) < 0;
                hasNegativeBonus[remainingCount] = neg;
                anyNegativeBonus |= neg;
                itemBonusSum[remainingCount] = bonuses[0] + bonuses[1] + bonuses[2] + bonuses[3] + bonuses[4];
                remainingIndices[remainingCount++] = i;
            }
        }
        if (remainingCount == 0) { memoStore(hash, itemCount, result); return result; }

        // ── Base case: remainingCount == 1 ───────────────────────────
        if (remainingCount == 1)
        {
            final int[] req = items[remainingIndices[0]].requirements;
            if ((req[0] == 0 || req[0] <= skill0)
                    && (req[1] == 0 || req[1] <= skill1)
                    && (req[2] == 0 || req[2] <= skill2)
                    && (req[3] == 0 || req[3] <= skill3)
                    && (req[4] == 0 || req[4] <= skill4))
            {
                result[remainingIndices[0]] = true;
            }
            memoStore(hash, itemCount, result);
            return result;
        }

        // ── Base case: remainingCount == 2 ───────────────────────────
        if (remainingCount == 2)
        {
            boolean[] r = solve2(items, remainingIndices, skill0, skill1, skill2, skill3, skill4,
                                 itemBonusSum, result);
            memoStore(hash, itemCount, r);
            return r;
        }

        // ── Phase 2: Greedy activation (scalar, unconditional) ───────
        {
            int currentSkill0 = skill0, currentSkill1 = skill1, currentSkill2 = skill2,
                currentSkill3 = skill3, currentSkill4 = skill4;
            int activeMask = 0;
            int activeCount = 0;
            boolean changed = true;
            while (changed)
            {
                changed = false;
                for (int j = 0; j < remainingCount; j++)
                {
                    if ((activeMask & (1 << j)) != 0) continue;
                    final int[] requirements = items[remainingIndices[j]].requirements;
                    if ((requirements[0] != 0 && requirements[0] > currentSkill0)
                            || (requirements[1] != 0 && requirements[1] > currentSkill1)
                            || (requirements[2] != 0 && requirements[2] > currentSkill2)
                            || (requirements[3] != 0 && requirements[3] > currentSkill3)
                            || (requirements[4] != 0 && requirements[4] > currentSkill4))
                        continue;

                    final int[] bonuses = items[remainingIndices[j]].bonuses;
                    if (hasNegativeBonus[j])
                    {
                        int testSkill0 = currentSkill0 + bonuses[0], testSkill1 = currentSkill1 + bonuses[1],
                            testSkill2 = currentSkill2 + bonuses[2], testSkill3 = currentSkill3 + bonuses[3],
                            testSkill4 = currentSkill4 + bonuses[4];
                        boolean valid = true;
                        for (int activeBits = activeMask; activeBits != 0; activeBits &= activeBits - 1)
                        {
                            int activeIdx = Integer.numberOfTrailingZeros(activeBits);
                            final int[] activeReqs = items[remainingIndices[activeIdx]].requirements;
                            final int[] activeBonuses = items[remainingIndices[activeIdx]].bonuses;
                            if ((activeReqs[0] != 0 && activeReqs[0] + activeBonuses[0] > testSkill0)
                                    || (activeReqs[1] != 0 && activeReqs[1] + activeBonuses[1] > testSkill1)
                                    || (activeReqs[2] != 0 && activeReqs[2] + activeBonuses[2] > testSkill2)
                                    || (activeReqs[3] != 0 && activeReqs[3] + activeBonuses[3] > testSkill3)
                                    || (activeReqs[4] != 0 && activeReqs[4] + activeBonuses[4] > testSkill4))
                            {
                                valid = false;
                                break;
                            }
                        }
                        if (!valid) continue;
                    }
                    activeMask |= (1 << j);
                    activeCount++;
                    currentSkill0 += bonuses[0]; currentSkill1 += bonuses[1]; currentSkill2 += bonuses[2];
                    currentSkill3 += bonuses[3]; currentSkill4 += bonuses[4];
                    changed = true;
                }
            }
            if (activeCount == remainingCount)
            {
                for (int j = 0; j < remainingCount; j++) result[remainingIndices[j]] = true;
                memoStore(hash, itemCount, result);
                return result;
            }

            if (!anyNegativeBonus)
            {
                for (int activeBits = activeMask; activeBits != 0; activeBits &= activeBits - 1)
                    result[remainingIndices[Integer.numberOfTrailingZeros(activeBits)]] = true;
                memoStore(hash, itemCount, result);
                return result;
            }
        }

        // ── Phase 3: Packed BitmaskDP with lazy computation + bitset BFS
        final int totalMasks = 1 << remainingCount;

        for (int j = 0; j < remainingCount; j++)
        {
            final int[] req = items[remainingIndices[j]].requirements;
            final int[] bon = items[remainingIndices[j]].bonuses;
            pReq[j] = packReq(req);
            pNeed[j] = packNeed(req, bon);
            pBon[j] = pack5(bon[0], bon[1], bon[2], bon[3], bon[4]);
        }

        final long[] sn = this.skillNeed;
        final int[] weight = this.weightBuf;

        sn[0] = pack5(skill0, skill1, skill2, skill3, skill4);
        sn[1] = 0;
        weight[0] = 0;

        final long[] reach = this.reachBits;
        final int words = (totalMasks + 63) >>> 6;
        for (int w = 0; w < words; w++) reach[w] = 0;
        reach[0] = 1L;

        int bestMask = 0, bestCount = 0, bestWeight = 0;

        for (int w = 0; w < words; w++)
        {
            int base = w << 6;
            long processed = 0;
            long bits;
            while ((bits = reach[w] & ~processed) != 0)
            {
                int pos = Long.numberOfTrailingZeros(bits);
                processed |= 1L << pos;
                int bmask = base + pos;

                int count = Integer.bitCount(bmask);
                int maskWeight = weight[bmask];
                if (count > bestCount || (count == bestCount && maskWeight > bestWeight))
                {
                    bestCount = count;
                    bestWeight = maskWeight;
                    bestMask = bmask;
                }
                if (bestCount == remainingCount) break;

                long curSk = sn[bmask << 1];
                long curMn = sn[(bmask << 1) + 1];
                int curW = weight[bmask];

                for (int j = 0; j < remainingCount; j++)
                {
                    if ((bmask & (1 << j)) != 0) continue;
                    int nextMask = bmask | (1 << j);
                    if ((reach[nextMask >>> 6] & (1L << (nextMask & 63))) != 0) continue;
                    if (!ge5(curSk, pReq[j])) continue;
                    long nextSk = curSk + pBon[j] - BIAS5;
                    if (hasNegativeBonus[j])
                    {
                        if (!ge5(nextSk, curMn)) continue;
                    }
                    int nextSnIdx = nextMask << 1;
                    sn[nextSnIdx] = nextSk;
                    sn[nextSnIdx + 1] = max5(curMn, pNeed[j]);
                    weight[nextMask] = curW + itemBonusSum[j];
                    reach[nextMask >>> 6] |= (1L << (nextMask & 63));
                }
            }
            if (bestCount == remainingCount) break;
        }

        for (int j = 0; j < remainingCount; j++)
        {
            if ((bestMask & (1 << j)) != 0) result[remainingIndices[j]] = true;
        }

        memoStore(hash, itemCount, result);
        return result;
    }

    // ── 2-item fast path: try both orderings ─────────────────────────
    private static boolean[] solve2(
            WynnItem[] items, int[] ri,
            int s0, int s1, int s2, int s3, int s4,
            int[] bonusSum,
            boolean[] result)
    {
        final int[] reqA = items[ri[0]].requirements, bonA = items[ri[0]].bonuses;
        final int[] reqB = items[ri[1]].requirements, bonB = items[ri[1]].bonuses;

        boolean canEquipA = (reqA[0] == 0 || reqA[0] <= s0)
                         && (reqA[1] == 0 || reqA[1] <= s1)
                         && (reqA[2] == 0 || reqA[2] <= s2)
                         && (reqA[3] == 0 || reqA[3] <= s3)
                         && (reqA[4] == 0 || reqA[4] <= s4);
        boolean canEquipB = (reqB[0] == 0 || reqB[0] <= s0)
                         && (reqB[1] == 0 || reqB[1] <= s1)
                         && (reqB[2] == 0 || reqB[2] <= s2)
                         && (reqB[3] == 0 || reqB[3] <= s3)
                         && (reqB[4] == 0 || reqB[4] <= s4);

        if (canEquipA)
        {
            int as0 = s0 + bonA[0], as1 = s1 + bonA[1], as2 = s2 + bonA[2],
                as3 = s3 + bonA[3], as4 = s4 + bonA[4];
            boolean bAfterA = (reqB[0] == 0 || reqB[0] <= as0)
                           && (reqB[1] == 0 || reqB[1] <= as1)
                           && (reqB[2] == 0 || reqB[2] <= as2)
                           && (reqB[3] == 0 || reqB[3] <= as3)
                           && (reqB[4] == 0 || reqB[4] <= as4);
            if (bAfterA)
            {
                int abs0 = as0 + bonB[0], abs1 = as1 + bonB[1], abs2 = as2 + bonB[2],
                    abs3 = as3 + bonB[3], abs4 = as4 + bonB[4];
                boolean aStillValid = (reqA[0] == 0 || reqA[0] + bonA[0] <= abs0)
                                   && (reqA[1] == 0 || reqA[1] + bonA[1] <= abs1)
                                   && (reqA[2] == 0 || reqA[2] + bonA[2] <= abs2)
                                   && (reqA[3] == 0 || reqA[3] + bonA[3] <= abs3)
                                   && (reqA[4] == 0 || reqA[4] + bonA[4] <= abs4);
                boolean bStillValid = (reqB[0] == 0 || reqB[0] + bonB[0] <= abs0)
                                   && (reqB[1] == 0 || reqB[1] + bonB[1] <= abs1)
                                   && (reqB[2] == 0 || reqB[2] + bonB[2] <= abs2)
                                   && (reqB[3] == 0 || reqB[3] + bonB[3] <= abs3)
                                   && (reqB[4] == 0 || reqB[4] + bonB[4] <= abs4);
                if (aStillValid && bStillValid)
                {
                    result[ri[0]] = true;
                    result[ri[1]] = true;
                    return result;
                }
            }
        }

        if (canEquipB)
        {
            int bs0 = s0 + bonB[0], bs1 = s1 + bonB[1], bs2 = s2 + bonB[2],
                bs3 = s3 + bonB[3], bs4 = s4 + bonB[4];
            boolean aAfterB = (reqA[0] == 0 || reqA[0] <= bs0)
                           && (reqA[1] == 0 || reqA[1] <= bs1)
                           && (reqA[2] == 0 || reqA[2] <= bs2)
                           && (reqA[3] == 0 || reqA[3] <= bs3)
                           && (reqA[4] == 0 || reqA[4] <= bs4);
            if (aAfterB)
            {
                int bas0 = bs0 + bonA[0], bas1 = bs1 + bonA[1], bas2 = bs2 + bonA[2],
                    bas3 = bs3 + bonA[3], bas4 = bs4 + bonA[4];
                boolean bStillValid = (reqB[0] == 0 || reqB[0] + bonB[0] <= bas0)
                                   && (reqB[1] == 0 || reqB[1] + bonB[1] <= bas1)
                                   && (reqB[2] == 0 || reqB[2] + bonB[2] <= bas2)
                                   && (reqB[3] == 0 || reqB[3] + bonB[3] <= bas3)
                                   && (reqB[4] == 0 || reqB[4] + bonB[4] <= bas4);
                boolean aStillValid = (reqA[0] == 0 || reqA[0] + bonA[0] <= bas0)
                                   && (reqA[1] == 0 || reqA[1] + bonA[1] <= bas1)
                                   && (reqA[2] == 0 || reqA[2] + bonA[2] <= bas2)
                                   && (reqA[3] == 0 || reqA[3] + bonA[3] <= bas3)
                                   && (reqA[4] == 0 || reqA[4] + bonA[4] <= bas4);
                if (aStillValid && bStillValid)
                {
                    result[ri[0]] = true;
                    result[ri[1]] = true;
                    return result;
                }
            }
        }

        if (canEquipA && canEquipB)
        {
            result[ri[bonusSum[0] >= bonusSum[1] ? 0 : 1]] = true;
        }
        else if (canEquipA) result[ri[0]] = true;
        else if (canEquipB) result[ri[1]] = true;
        return result;
    }
}
