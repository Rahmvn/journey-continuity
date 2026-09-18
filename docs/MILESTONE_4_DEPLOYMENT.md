# Milestone 4 Deployment

Milestone 4 keeps monitoring transitions in PostgreSQL. The AWS Lambda does not reproduce watchdog rules; it only invokes `evaluate_due_journeys()` once per Scheduler delivery.

## 1. Supabase migration

1. Review and apply `supabase/migrations/20260916000100_milestone_4_cloud_watchdog.sql` through the existing development-project migration workflow.
2. Confirm the three monitoring tables have RLS enabled.
3. Confirm `authenticated` has read-only table access and execute access only to `record_journey_heartbeat(...)`.
4. Confirm only `service_role` can execute `evaluate_due_journeys()`.
5. Run `supabase/tests/milestone_4_watchdog_test.sql` in an isolated test database when available. Do not run the rollback-based fixture against user-owned production data.

## 2. Build and deploy AWS resources disabled

Prerequisites: authenticated AWS CLI and AWS SAM CLI for the intended development account and Region.

```powershell
Set-Location aws/watchdog
sam validate --lint
sam build
sam deploy --guided --parameter-overrides ScheduleState=DISABLED
```

Use a development stack name such as `journey-continuity-dev-watchdog`. Review the change set before approval. The stack creates one Lambda, one disabled minute schedule, least-privilege execution roles, a 14-day log group, and an empty Secrets Manager secret.

## 3. Populate the secret

Read `WatchdogSecretArn` from the stack outputs. Populate it through the AWS console or a temporary local JSON file containing exactly:

```json
{
  "SUPABASE_URL": "https://your-project-ref.supabase.co",
  "SUPABASE_SECRET_KEY": "sb_secret_your_server_key"
}
```

Never commit that file. With a safely created temporary file, upload it without placing the key in the command line:

```powershell
aws secretsmanager put-secret-value --secret-id <WatchdogSecretArn> --secret-string file://watchdog-secret.local.json
```

Securely delete the temporary file after verifying the secret version. Do not use the Android publishable key or a legacy service-role key.

## 4. Qualify before enabling

Invoke the Lambda once while the schedule remains disabled:

```powershell
aws lambda invoke --function-name <stack-name>-watchdog watchdog-result.local.json
```

Confirm a successful response and a structured `watchdog_evaluated` log containing only evaluation time and transition counts. No Supabase key or response body should appear in CloudWatch.

## 5. Enable the shared schedule

Redeploy the same stack configuration with:

```powershell
sam deploy --parameter-overrides ScheduleState=ENABLED
```

Confirm exactly one EventBridge Scheduler schedule exists and uses `rate(1 minute)`. Scheduler delivery is minute-resolution and is not an exact-second timer. Do not create schedules per Journey.

