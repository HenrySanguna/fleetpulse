-- Wires pg_partman onto the `positions` parent declared in V5 (change 03,
-- section "pg_partman y mantenimiento programado"). create_parent() creates
-- the current partition plus p_premake partitions ahead *immediately*, at
-- migration time -- not only when partman.run_maintenance_proc() later
-- runs -- which is what satisfies the DoD item "las particiones de la
-- semana siguiente existen antes de que empiece esa semana" from the very
-- first deploy. Keeping that true as weeks keep passing after this one-time
-- call is the @Scheduled task's job (processor module, PartitionMaintenanceTask,
-- task 1.5), invoking partman.run_maintenance_proc() explicitly (design.md)
-- because Neon suspends compute on idle and pg_partman's own background
-- worker never runs during that suspension (project.md).
--
-- p_interval must be a real PostgreSQL interval value: pg_partman 5.x
-- rejects the pre-5.0 special keyword 'weekly' ("Special partition interval
-- values from old pg_partman versions ... are no longer supported").
SELECT partman.create_parent(
    p_parent_table := 'public.positions',
    p_control      := 'recorded_at',
    p_interval     := '1 week',
    p_premake      := 4
);

-- "Retencion configurable" (task 1.3): the retention window lives in
-- pg_partman's own partman.part_config table, adjustable with a plain
-- UPDATE at any time -- not a value hardcoded in application code. 90 days
-- is this change's starting default; neither design.md nor proposal.md
-- states a required retention period, so this is a documented decision,
-- not a spec requirement, and can be revised by updating this one row.
-- retention_keep_table = false so retention actually drops the old
-- partition's storage instead of merely detaching and keeping it around.
UPDATE partman.part_config
SET retention = '90 days',
    retention_keep_table = false
WHERE parent_table = 'public.positions';
