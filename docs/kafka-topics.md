# Kafka Topic Status

Tracking implementation progress for all Kafka topics used by the system.

## Consumer Topics

| # | Topic                                                                                  | Message Type | Implemented | Well Tested |
|---|----------------------------------------------------------------------------------------|-------------|:-----------:|:-----------:|
| 0 | `APMT.terminalOperations.workInstruction.topic.confidential.dedicated.v1`              | WorkInstruction |     Yes     |     No      |
| 1 | `APMT.terminalOperations.workQueue.topic.confidential.dedicated.v1`                    | WorkQueue |     Yes     |     No      |
| 2 | `APMT.terminalOperations.containerHandlingEquipment.topic.confidential.dedicated.v1`   | ContainerHandlingEquipment |     Yes     |     Yes     |
| 3 | `apmt.terminaloperations.digitalmap.topic.confidential.dedicated.v1`                   | TerminalLayout |     Yes     |     Yes     |
| 4 | `apmt.terminaloperations.joboperation.topic.confidential.dedicated.v1`                 | JobOperation |     Yes      |     No      |
| 5 | `apmt.terminaloperations.chetargetposition.topic.confidential.dedicated.v1`            | CheTargetPositionConfirmation |     Yes      |     No      |
| 6 | `apmt.terminaloperations.assetevent.rubbertyredgantry.topic.confidential.dedicated.v1` | AssetEvent (RTG) |     Yes     |     No      |
| 7 | `apmt.terminaloperations.assetevent.quaycrane.topic.confidential.dedicated.v1`         | AssetEvent (QC) |     Yes     |     No      |
| 8 | `apmt.terminaloperations.assetevent.emptyhandler.topic.confidential.dedicated.v1`      | AssetEvent (EH) |     Yes     |     No      |
| 9 | `apmt.quaysideoperations.quaycraneflowposition.topic.internal.any.v2`                  | QuayCraneMapping |     Yes     |     No      |
| 10 | `apmt.terminaloperations.chelogicalposition.topic.confidential.dedicated.v1`           | CheLogicalPosition |     Yes     |     Yes     |
| 11 | `APMT.terminalOperations.craneDelayActivities.topic.confidential.dedicated.v1`         | CraneDelayActivities |     Yes     |     Yes     |
| 12 | `apmt.terminaloperations.craneavailabilitystatus.topic.confidential.dedicated.v1`      | CraneAvailabilityStatus |     Yes     |     Yes     |
| 13 | `apmt.terminaloperations.containermovestate.topic.confidential.status.v1`              | ContainerMoveState |     Yes     |     Yes     |
| 14 | `apmt.terminal-operations.flow-scheduling-standards.topic.internal.any.v1`             | FlowSchedulingStandards |     No      |     No      |
| 15 | `apmt.terminal-operations.cranereadiness.topic.internal.any.v1`                        | CraneReadiness |     Yes     |     Yes     |

## Producer Topics

| # | Topic | Message Type | CHE Type | Implemented | Well Tested |
|---|-------|-------------|----------|:-----------:|:-----------:|
| 0 | `apmt.terminaloperations.equipmentinstruction.rubbertyredgantry.topic.confidential.dedicated.v1` | EquipmentInstruction | RTG | Yes | No |
| 1 | `apmt.terminaloperations.equipmentinstruction.terminaltruck.topic.confidential.dedicated.v1` | EquipmentInstruction | TT | Yes | No |
| 2 | `apmt.terminaloperations.equipmentinstruction.quaycrane.topic.confidential.dedicated.v1` | EquipmentInstruction | QC | Yes | No |
| 3 | `apmt.terminaloperations.equipmentinstruction.emptyhandler.topic.confidential.dedicated.v1` | EquipmentInstruction | EH | No | No |
| 4 | `apmt.terminaloperations.containermovestate.topic.confidential.dedicated.v1` | ContainerMoveState | TT | Yes | Yes |
| 5 | `apmt.terminaloperations.flowstatus.topic.confidential.dedicated.v4` | FlowStatusV4 | - | No | No |
| 6 | `apmt.deviationmanagement.exception.topic.internal.status.v1` | FlowException | - | No | No |
| 7 | `apmt.terminaloperations.flowanalytics-taktreport.topic.internal.any.v2` | TAKTReport | - | No | No |
| 8 | `apmt.terminaloperations.flowpreliminaryplan.topic.internal.any.v1` | FlowPreliminaryPlan | - | No | No |
| 9 | `apmt.terminal-operations.flow-terminal-truck-forecast.topic.internal.any.v2` | FlowTerminalTruckForecast | - | No | No |
| 10 | `apmt.terminal-operations.flow-work-execution-state.topic.internal.any.v1` | FlowWork | - | No | No |

## Summary

| Direction | Total | Implemented | Well Tested (SIT READY) |
|-----------|:-----:|:-----------:|:-----------------------:|
| Consumer  | 16    |     13      |            7            |
| Producer  | 11    |      4      |            1            |
| **Total** | **27**|   **19**    |          **8**          |
