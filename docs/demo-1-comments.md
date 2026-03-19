# Comment

P1
4. DONE RESET delets all twins
5. DONE TWIN DISCH lift single, put twins (RTG drive should wait for both QC Container DSCH)
6. DONE Workinstruction is missing jobPositions (AFT, FWD)
10. when lift as single is active, but we QC Discharged Container for the next LIFT TWIN, no reschedule
11. Rumble - Reschedule, doesn't work anymore in general
12. DONE `container move state` is a producer, where FES writes TT assignment, fetch che instruction sent, fetch che completed ....
13. DONE `container move state status` is a consumer, where FES reads writeback failure (if failure is TT assignment failed, FES should unassign this TT and try find a new one)

P2
1. TT should not go to pull, if standby is free for QC
2. TT should not go to pull, if standby is free for RTG
3. in DSCH TT can go straight to under RTG is position free

P3
7. DONE quay cranes availability che type is STS (which is wrong, should be QC)
8. pathfinder shows one bollard B81.5? somewhere in the see, is that really so? data or parsing issue?
9. DONE Crane delay, the last one should be highlighted as the active message, even if it says delay stop. or maybe make the DOT green for the last delay crane
14. total consumed message count doesnt work when triggering chelogicalposition 10k flood




