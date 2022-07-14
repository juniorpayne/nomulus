-- Copyright 2022 The Nomulus Authors. All Rights Reserved.
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.

alter table "Domain" drop column billing_recurrence_history_id;
alter table "Domain" drop column deletion_poll_message_history_id;
alter table "Domain" drop column transfer_billing_cancellation_history_id;
alter table "Domain" drop column transfer_billing_recurrence_history_id;
alter table "Domain" drop column transfer_billing_event_history_id;

alter table "DomainHistory" drop column billing_recurrence_history_id;
alter table "DomainHistory" drop column deletion_poll_message_history_id;
alter table "DomainHistory" drop column transfer_billing_cancellation_history_id;
alter table "DomainHistory" drop column transfer_billing_recurrence_history_id;
alter table "DomainHistory" drop column transfer_billing_event_history_id;

alter table "GracePeriod" drop column billing_event_history_id;
alter table "GracePeriod" drop column billing_event_domain_repo_id;
alter table "GracePeriod" drop column billing_recurrence_history_id;
alter table "GracePeriod" drop column billing_recurrence_domain_repo_id;

alter table "GracePeriodHistory" drop column billing_event_history_id;
alter table "GracePeriodHistory" drop column billing_event_domain_repo_id;
alter table "GracePeriodHistory" drop column billing_recurrence_history_id;
alter table "GracePeriodHistory" drop column billing_recurrence_domain_repo_id;
