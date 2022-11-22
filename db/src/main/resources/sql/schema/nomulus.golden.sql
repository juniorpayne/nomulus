--
-- PostgreSQL database dump
--

-- Dumped from database version 11.14 (Debian 11.14-1.pgdg90+1)
-- Dumped by pg_dump version 11.14 (Debian 11.14-1.pgdg90+1)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: hstore; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS hstore WITH SCHEMA public;


--
-- Name: EXTENSION hstore; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION hstore IS 'data type for storing sets of (key, value) pairs';


SET default_tablespace = '';

SET default_with_oids = false;

--
-- Name: AllocationToken; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."AllocationToken" (
    token text NOT NULL,
    update_timestamp timestamp with time zone,
    allowed_registrar_ids text[],
    allowed_tlds text[],
    creation_time timestamp with time zone NOT NULL,
    discount_fraction double precision NOT NULL,
    discount_premiums boolean NOT NULL,
    discount_years integer NOT NULL,
    domain_name text,
    redemption_domain_repo_id text,
    token_status_transitions public.hstore,
    token_type text,
    redemption_domain_history_id bigint,
    renewal_price_behavior text DEFAULT 'DEFAULT'::text NOT NULL,
    registration_behavior text DEFAULT 'DEFAULT'::text NOT NULL
);


--
-- Name: BillingCancellation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."BillingCancellation" (
    billing_cancellation_id bigint NOT NULL,
    registrar_id text NOT NULL,
    domain_history_revision_id bigint NOT NULL,
    domain_repo_id text NOT NULL,
    event_time timestamp with time zone NOT NULL,
    flags text[],
    reason text NOT NULL,
    domain_name text NOT NULL,
    billing_time timestamp with time zone,
    billing_event_id bigint,
    billing_recurrence_id bigint
);


--
-- Name: BillingEvent; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."BillingEvent" (
    billing_event_id bigint NOT NULL,
    registrar_id text NOT NULL,
    domain_history_revision_id bigint NOT NULL,
    domain_repo_id text NOT NULL,
    event_time timestamp with time zone NOT NULL,
    flags text[],
    reason text NOT NULL,
    domain_name text NOT NULL,
    allocation_token text,
    billing_time timestamp with time zone,
    cancellation_matching_billing_recurrence_id bigint,
    cost_amount numeric(19,2),
    cost_currency text,
    period_years integer,
    synthetic_creation_time timestamp with time zone,
    recurrence_history_revision_id bigint
);


--
-- Name: BillingRecurrence; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."BillingRecurrence" (
    billing_recurrence_id bigint NOT NULL,
    registrar_id text NOT NULL,
    domain_history_revision_id bigint NOT NULL,
    domain_repo_id text NOT NULL,
    event_time timestamp with time zone NOT NULL,
    flags text[],
    reason text NOT NULL,
    domain_name text NOT NULL,
    recurrence_end_time timestamp with time zone,
    recurrence_time_of_year text,
    renewal_price_behavior text DEFAULT 'DEFAULT'::text NOT NULL,
    renewal_price_currency text,
    renewal_price_amount numeric(19,2),
    recurrence_last_expansion timestamp with time zone DEFAULT '2021-06-01 00:00:00+00'::timestamp with time zone NOT NULL
);


--
-- Name: ClaimsEntry; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."ClaimsEntry" (
    revision_id bigint NOT NULL,
    claim_key text NOT NULL,
    domain_label text NOT NULL
);


--
-- Name: ClaimsList; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."ClaimsList" (
    revision_id bigint NOT NULL,
    creation_timestamp timestamp with time zone NOT NULL,
    tmdb_generation_time timestamp with time zone NOT NULL
);


--
-- Name: ClaimsList_revision_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."ClaimsList_revision_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ClaimsList_revision_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public."ClaimsList_revision_id_seq" OWNED BY public."ClaimsList".revision_id;


--
-- Name: Contact; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Contact" (
    repo_id text NOT NULL,
    creation_registrar_id text NOT NULL,
    creation_time timestamp with time zone NOT NULL,
    current_sponsor_registrar_id text NOT NULL,
    deletion_time timestamp with time zone,
    last_epp_update_registrar_id text,
    last_epp_update_time timestamp with time zone,
    statuses text[],
    auth_info_repo_id text,
    auth_info_value text,
    contact_id text,
    disclose_types_addr text[],
    disclose_show_email boolean,
    disclose_show_fax boolean,
    disclose_mode_flag boolean,
    disclose_types_name text[],
    disclose_types_org text[],
    disclose_show_voice boolean,
    email text,
    fax_phone_extension text,
    fax_phone_number text,
    addr_i18n_city text,
    addr_i18n_country_code text,
    addr_i18n_state text,
    addr_i18n_street_line1 text,
    addr_i18n_street_line2 text,
    addr_i18n_street_line3 text,
    addr_i18n_zip text,
    addr_i18n_name text,
    addr_i18n_org text,
    addr_i18n_type text,
    last_transfer_time timestamp with time zone,
    addr_local_city text,
    addr_local_country_code text,
    addr_local_state text,
    addr_local_street_line1 text,
    addr_local_street_line2 text,
    addr_local_street_line3 text,
    addr_local_zip text,
    addr_local_name text,
    addr_local_org text,
    addr_local_type text,
    search_name text,
    voice_phone_extension text,
    voice_phone_number text,
    transfer_poll_message_id_1 bigint,
    transfer_poll_message_id_2 bigint,
    transfer_client_txn_id text,
    transfer_server_txn_id text,
    transfer_gaining_registrar_id text,
    transfer_losing_registrar_id text,
    transfer_pending_expiration_time timestamp with time zone,
    transfer_request_time timestamp with time zone,
    transfer_status text,
    update_timestamp timestamp with time zone,
    transfer_history_entry_id bigint,
    transfer_repo_id text,
    transfer_poll_message_id_3 bigint
);


--
-- Name: ContactHistory; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."ContactHistory" (
    history_revision_id bigint NOT NULL,
    history_by_superuser boolean NOT NULL,
    history_registrar_id text,
    history_modification_time timestamp with time zone NOT NULL,
    history_reason text,
    history_requested_by_registrar boolean,
    history_client_transaction_id text,
    history_server_transaction_id text,
    history_type text NOT NULL,
    history_xml_bytes bytea,
    auth_info_repo_id text,
    auth_info_value text,
    contact_id text,
    disclose_types_addr text[],
    disclose_show_email boolean,
    disclose_show_fax boolean,
    disclose_mode_flag boolean,
    disclose_types_name text[],
    disclose_types_org text[],
    disclose_show_voice boolean,
    email text,
    fax_phone_extension text,
    fax_phone_number text,
    addr_i18n_city text,
    addr_i18n_country_code text,
    addr_i18n_state text,
    addr_i18n_street_line1 text,
    addr_i18n_street_line2 text,
    addr_i18n_street_line3 text,
    addr_i18n_zip text,
    addr_i18n_name text,
    addr_i18n_org text,
    addr_i18n_type text,
    last_transfer_time timestamp with time zone,
    addr_local_city text,
    addr_local_country_code text,
    addr_local_state text,
    addr_local_street_line1 text,
    addr_local_street_line2 text,
    addr_local_street_line3 text,
    addr_local_zip text,
    addr_local_name text,
    addr_local_org text,
    addr_local_type text,
    search_name text,
    transfer_poll_message_id_1 bigint,
    transfer_poll_message_id_2 bigint,
    transfer_client_txn_id text,
    transfer_server_txn_id text,
    transfer_gaining_registrar_id text,
    transfer_losing_registrar_id text,
    transfer_pending_expiration_time timestamp with time zone,
    transfer_request_time timestamp with time zone,
    transfer_status text,
    voice_phone_extension text,
    voice_phone_number text,
    creation_registrar_id text,
    creation_time timestamp with time zone,
    current_sponsor_registrar_id text,
    deletion_time timestamp with time zone,
    last_epp_update_registrar_id text,
    last_epp_update_time timestamp with time zone,
    statuses text[],
    contact_repo_id text NOT NULL,
    update_timestamp timestamp with time zone,
    transfer_history_entry_id bigint,
    transfer_repo_id text,
    transfer_poll_message_id_3 bigint
);


--
-- Name: Cursor; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Cursor" (
    scope text NOT NULL,
    type text NOT NULL,
    cursor_time timestamp with time zone NOT NULL,
    last_update_time timestamp with time zone NOT NULL
);


--
-- Name: DatabaseMigrationStateSchedule; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."DatabaseMigrationStateSchedule" (
    id bigint NOT NULL,
    migration_transitions public.hstore
);


--
-- Name: DelegationSignerData; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."DelegationSignerData" (
    domain_repo_id text NOT NULL,
    key_tag integer NOT NULL,
    algorithm integer NOT NULL,
    digest bytea NOT NULL,
    digest_type integer NOT NULL
);


--
-- Name: Domain; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Domain" (
    repo_id text NOT NULL,
    creation_registrar_id text NOT NULL,
    creation_time timestamp with time zone NOT NULL,
    current_sponsor_registrar_id text NOT NULL,
    deletion_time timestamp with time zone,
    last_epp_update_registrar_id text,
    last_epp_update_time timestamp with time zone,
    statuses text[],
    auth_info_repo_id text,
    auth_info_value text,
    domain_name text,
    idn_table_name text,
    last_transfer_time timestamp with time zone,
    launch_notice_accepted_time timestamp with time zone,
    launch_notice_expiration_time timestamp with time zone,
    launch_notice_tcn_id text,
    launch_notice_validator_id text,
    registration_expiration_time timestamp with time zone,
    smd_id text,
    subordinate_hosts text[],
    tld text,
    admin_contact text,
    billing_contact text,
    registrant_contact text,
    tech_contact text,
    transfer_poll_message_id_1 bigint,
    transfer_poll_message_id_2 bigint,
    transfer_billing_cancellation_id bigint,
    transfer_billing_event_id bigint,
    transfer_billing_recurrence_id bigint,
    transfer_autorenew_poll_message_id bigint,
    transfer_renew_period_unit text,
    transfer_renew_period_value integer,
    transfer_client_txn_id text,
    transfer_server_txn_id text,
    transfer_registration_expiration_time timestamp with time zone,
    transfer_gaining_registrar_id text,
    transfer_losing_registrar_id text,
    transfer_pending_expiration_time timestamp with time zone,
    transfer_request_time timestamp with time zone,
    transfer_status text,
    update_timestamp timestamp with time zone,
    billing_recurrence_id bigint,
    autorenew_poll_message_id bigint,
    deletion_poll_message_id bigint,
    autorenew_end_time timestamp with time zone,
    transfer_autorenew_poll_message_history_id bigint,
    transfer_history_entry_id bigint,
    transfer_repo_id text,
    transfer_poll_message_id_3 bigint,
    dns_refresh_request_time timestamp with time zone,
    current_package_token text
);


--
-- Name: DomainDsDataHistory; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."DomainDsDataHistory" (
    ds_data_history_revision_id bigint NOT NULL,
    algorithm integer NOT NULL,
    digest bytea NOT NULL,
    digest_type integer NOT NULL,
    domain_history_revision_id bigint NOT NULL,
    key_tag integer NOT NULL,
    domain_repo_id text
);


--
-- Name: DomainHistory; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."DomainHistory" (
    history_revision_id bigint NOT NULL,
    history_by_superuser boolean NOT NULL,
    history_registrar_id text,
    history_modification_time timestamp with time zone NOT NULL,
    history_reason text,
    history_requested_by_registrar boolean,
    history_client_transaction_id text,
    history_server_transaction_id text,
    history_type text NOT NULL,
    history_xml_bytes bytea,
    admin_contact text,
    auth_info_repo_id text,
    auth_info_value text,
    billing_recurrence_id bigint,
    autorenew_poll_message_id bigint,
    billing_contact text,
    deletion_poll_message_id bigint,
    domain_name text,
    idn_table_name text,
    last_transfer_time timestamp with time zone,
    launch_notice_accepted_time timestamp with time zone,
    launch_notice_expiration_time timestamp with time zone,
    launch_notice_tcn_id text,
    launch_notice_validator_id text,
    registrant_contact text,
    registration_expiration_time timestamp with time zone,
    smd_id text,
    subordinate_hosts text[],
    tech_contact text,
    tld text,
    transfer_billing_cancellation_id bigint,
    transfer_billing_recurrence_id bigint,
    transfer_autorenew_poll_message_id bigint,
    transfer_billing_event_id bigint,
    transfer_renew_period_unit text,
    transfer_renew_period_value integer,
    transfer_registration_expiration_time timestamp with time zone,
    transfer_poll_message_id_1 bigint,
    transfer_poll_message_id_2 bigint,
    transfer_client_txn_id text,
    transfer_server_txn_id text,
    transfer_gaining_registrar_id text,
    transfer_losing_registrar_id text,
    transfer_pending_expiration_time timestamp with time zone,
    transfer_request_time timestamp with time zone,
    transfer_status text,
    creation_registrar_id text,
    creation_time timestamp with time zone,
    current_sponsor_registrar_id text,
    deletion_time timestamp with time zone,
    last_epp_update_registrar_id text,
    last_epp_update_time timestamp with time zone,
    statuses text[],
    update_timestamp timestamp with time zone,
    domain_repo_id text NOT NULL,
    autorenew_end_time timestamp with time zone,
    history_other_registrar_id text,
    history_period_unit text,
    history_period_value integer,
    autorenew_poll_message_history_id bigint,
    transfer_autorenew_poll_message_history_id bigint,
    transfer_history_entry_id bigint,
    transfer_repo_id text,
    transfer_poll_message_id_3 bigint,
    dns_refresh_request_time timestamp with time zone,
    current_package_token text
);


--
-- Name: DomainHistoryHost; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."DomainHistoryHost" (
    domain_history_history_revision_id bigint NOT NULL,
    host_repo_id text,
    domain_history_domain_repo_id text NOT NULL
);


--
-- Name: DomainHost; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."DomainHost" (
    domain_repo_id text NOT NULL,
    host_repo_id text
);


--
-- Name: DomainTransactionRecord; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."DomainTransactionRecord" (
    id bigint NOT NULL,
    report_amount integer NOT NULL,
    report_field text NOT NULL,
    reporting_time timestamp with time zone NOT NULL,
    tld text NOT NULL,
    domain_repo_id text,
    history_revision_id bigint
);


--
-- Name: DomainTransactionRecord_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."DomainTransactionRecord_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: DomainTransactionRecord_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public."DomainTransactionRecord_id_seq" OWNED BY public."DomainTransactionRecord".id;


--
-- Name: GracePeriod; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."GracePeriod" (
    grace_period_id bigint NOT NULL,
    billing_event_id bigint,
    billing_recurrence_id bigint,
    registrar_id text NOT NULL,
    domain_repo_id text NOT NULL,
    expiration_time timestamp with time zone NOT NULL,
    type text NOT NULL
);


--
-- Name: GracePeriodHistory; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."GracePeriodHistory" (
    grace_period_history_revision_id bigint NOT NULL,
    billing_event_id bigint,
    billing_recurrence_id bigint,
    registrar_id text NOT NULL,
    domain_repo_id text NOT NULL,
    expiration_time timestamp with time zone NOT NULL,
    type text NOT NULL,
    domain_history_revision_id bigint,
    grace_period_id bigint NOT NULL
);


--
-- Name: Host; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Host" (
    repo_id text NOT NULL,
    creation_registrar_id text,
    creation_time timestamp with time zone,
    current_sponsor_registrar_id text,
    deletion_time timestamp with time zone,
    last_epp_update_registrar_id text,
    last_epp_update_time timestamp with time zone,
    statuses text[],
    host_name text,
    last_superordinate_change timestamp with time zone,
    last_transfer_time timestamp with time zone,
    superordinate_domain text,
    inet_addresses text[],
    update_timestamp timestamp with time zone,
    transfer_poll_message_id_3 bigint
);


--
-- Name: HostHistory; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."HostHistory" (
    history_revision_id bigint NOT NULL,
    history_by_superuser boolean NOT NULL,
    history_registrar_id text NOT NULL,
    history_modification_time timestamp with time zone NOT NULL,
    history_reason text,
    history_requested_by_registrar boolean,
    history_client_transaction_id text,
    history_server_transaction_id text,
    history_type text NOT NULL,
    history_xml_bytes bytea,
    host_name text,
    inet_addresses text[],
    last_superordinate_change timestamp with time zone,
    last_transfer_time timestamp with time zone,
    superordinate_domain text,
    creation_registrar_id text,
    creation_time timestamp with time zone,
    current_sponsor_registrar_id text,
    deletion_time timestamp with time zone,
    last_epp_update_registrar_id text,
    last_epp_update_time timestamp with time zone,
    statuses text[],
    host_repo_id text NOT NULL,
    update_timestamp timestamp with time zone,
    transfer_poll_message_id_3 bigint
);


--
-- Name: Lock; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Lock" (
    resource_name text NOT NULL,
    scope text NOT NULL,
    acquired_time timestamp with time zone NOT NULL,
    expiration_time timestamp with time zone NOT NULL,
    request_log_id text NOT NULL
);


--
-- Name: PackagePromotion; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."PackagePromotion" (
    package_promotion_id bigint NOT NULL,
    last_notification_sent timestamp with time zone,
    max_creates integer NOT NULL,
    max_domains integer NOT NULL,
    next_billing_date timestamp with time zone NOT NULL,
    package_price_amount numeric(19,2) NOT NULL,
    package_price_currency text NOT NULL,
    token text NOT NULL
);


--
-- Name: Package_promotion_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."Package_promotion_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: Package_promotion_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public."Package_promotion_id_seq" OWNED BY public."PackagePromotion".package_promotion_id;


--
-- Name: PollMessage; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."PollMessage" (
    type text NOT NULL,
    poll_message_id bigint NOT NULL,
    registrar_id text NOT NULL,
    contact_repo_id text,
    contact_history_revision_id bigint,
    domain_repo_id text,
    domain_history_revision_id bigint,
    event_time timestamp with time zone NOT NULL,
    host_repo_id text,
    host_history_revision_id bigint,
    message text,
    transfer_response_contact_id text,
    transfer_response_domain_expiration_time timestamp with time zone,
    transfer_response_domain_name text,
    pending_action_response_action_result boolean,
    pending_action_response_name_or_id text,
    pending_action_response_processed_date timestamp with time zone,
    pending_action_response_client_txn_id text,
    pending_action_response_server_txn_id text,
    transfer_response_gaining_registrar_id text,
    transfer_response_losing_registrar_id text,
    transfer_response_pending_transfer_expiration_time timestamp with time zone,
    transfer_response_transfer_request_time timestamp with time zone,
    transfer_response_transfer_status text,
    autorenew_end_time timestamp with time zone,
    autorenew_domain_name text,
    transfer_response_host_id text
);


--
-- Name: PremiumEntry; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."PremiumEntry" (
    revision_id bigint NOT NULL,
    price numeric(19,2) NOT NULL,
    domain_label text NOT NULL
);


--
-- Name: PremiumList; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."PremiumList" (
    revision_id bigint NOT NULL,
    creation_timestamp timestamp with time zone,
    name text NOT NULL,
    bloom_filter bytea NOT NULL,
    currency text NOT NULL
);


--
-- Name: PremiumList_revision_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."PremiumList_revision_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: PremiumList_revision_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public."PremiumList_revision_id_seq" OWNED BY public."PremiumList".revision_id;


--
-- Name: RdeRevision; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."RdeRevision" (
    tld text NOT NULL,
    mode text NOT NULL,
    date date NOT NULL,
    update_timestamp timestamp with time zone,
    revision integer NOT NULL
);


--
-- Name: Registrar; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Registrar" (
    registrar_id text NOT NULL,
    allowed_tlds text[],
    billing_account_map public.hstore,
    block_premium_names boolean NOT NULL,
    client_certificate text,
    client_certificate_hash text,
    contacts_require_syncing boolean NOT NULL,
    creation_time timestamp with time zone NOT NULL,
    drive_folder_id text,
    email_address text,
    failover_client_certificate text,
    failover_client_certificate_hash text,
    fax_number text,
    iana_identifier bigint,
    icann_referral_email text,
    i18n_address_city text,
    i18n_address_country_code text,
    i18n_address_state text,
    i18n_address_street_line1 text,
    i18n_address_street_line2 text,
    i18n_address_street_line3 text,
    i18n_address_zip text,
    ip_address_allow_list text[],
    last_certificate_update_time timestamp with time zone,
    last_update_time timestamp with time zone NOT NULL,
    localized_address_city text,
    localized_address_country_code text,
    localized_address_state text,
    localized_address_street_line1 text,
    localized_address_street_line2 text,
    localized_address_street_line3 text,
    localized_address_zip text,
    password_hash text,
    phone_number text,
    phone_passcode text,
    po_number text,
    rdap_base_urls text[],
    registrar_name text NOT NULL,
    registry_lock_allowed boolean NOT NULL,
    password_salt text,
    state text,
    type text NOT NULL,
    url text,
    whois_server text,
    last_expiring_cert_notification_sent_date timestamp with time zone,
    last_expiring_failover_cert_notification_sent_date timestamp with time zone
);


--
-- Name: RegistrarPoc; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."RegistrarPoc" (
    email_address text NOT NULL,
    allowed_to_set_registry_lock_password boolean NOT NULL,
    fax_number text,
    gae_user_id text,
    name text,
    phone_number text,
    registry_lock_password_hash text,
    registry_lock_password_salt text,
    types text[],
    visible_in_domain_whois_as_abuse boolean NOT NULL,
    visible_in_whois_as_admin boolean NOT NULL,
    visible_in_whois_as_tech boolean NOT NULL,
    registry_lock_email_address text,
    registrar_id text NOT NULL,
    login_email_address text
);


--
-- Name: RegistryLock; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."RegistryLock" (
    revision_id bigint NOT NULL,
    lock_completion_time timestamp with time zone,
    lock_request_time timestamp with time zone NOT NULL,
    domain_name text NOT NULL,
    is_superuser boolean NOT NULL,
    registrar_id text NOT NULL,
    registrar_poc_id text,
    repo_id text NOT NULL,
    verification_code text NOT NULL,
    unlock_request_time timestamp with time zone,
    unlock_completion_time timestamp with time zone,
    last_update_time timestamp with time zone NOT NULL,
    relock_revision_id bigint,
    relock_duration interval
);


--
-- Name: RegistryLock_revision_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."RegistryLock_revision_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: RegistryLock_revision_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public."RegistryLock_revision_id_seq" OWNED BY public."RegistryLock".revision_id;


--
-- Name: ReservedEntry; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."ReservedEntry" (
    revision_id bigint NOT NULL,
    comment text,
    reservation_type integer NOT NULL,
    domain_label text NOT NULL
);


--
-- Name: ReservedList; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."ReservedList" (
    revision_id bigint NOT NULL,
    creation_timestamp timestamp with time zone NOT NULL,
    name text NOT NULL,
    should_publish boolean NOT NULL
);


--
-- Name: ReservedList_revision_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."ReservedList_revision_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ReservedList_revision_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public."ReservedList_revision_id_seq" OWNED BY public."ReservedList".revision_id;


--
-- Name: Spec11ThreatMatch; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Spec11ThreatMatch" (
    id bigint NOT NULL,
    check_date date NOT NULL,
    domain_name text NOT NULL,
    domain_repo_id text NOT NULL,
    registrar_id text NOT NULL,
    threat_types text[] NOT NULL,
    tld text NOT NULL
);


--
-- Name: SafeBrowsingThreat_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."SafeBrowsingThreat_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: SafeBrowsingThreat_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public."SafeBrowsingThreat_id_seq" OWNED BY public."Spec11ThreatMatch".id;


--
-- Name: ServerSecret; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."ServerSecret" (
    secret uuid NOT NULL,
    id bigint DEFAULT 1 NOT NULL
);


--
-- Name: SignedMarkRevocationEntry; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."SignedMarkRevocationEntry" (
    revision_id bigint NOT NULL,
    revocation_time timestamp with time zone NOT NULL,
    smd_id text NOT NULL
);


--
-- Name: SignedMarkRevocationList; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."SignedMarkRevocationList" (
    revision_id bigint NOT NULL,
    creation_time timestamp with time zone
);


--
-- Name: SignedMarkRevocationList_revision_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."SignedMarkRevocationList_revision_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: SignedMarkRevocationList_revision_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public."SignedMarkRevocationList_revision_id_seq" OWNED BY public."SignedMarkRevocationList".revision_id;


--
-- Name: Tld; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Tld" (
    tld_name text NOT NULL,
    add_grace_period_length interval NOT NULL,
    allowed_fully_qualified_host_names text[],
    allowed_registrant_contact_ids text[],
    anchor_tenant_add_grace_period_length interval NOT NULL,
    auto_renew_grace_period_length interval NOT NULL,
    automatic_transfer_length interval NOT NULL,
    claims_period_end timestamp with time zone NOT NULL,
    create_billing_cost_amount numeric(19,2),
    create_billing_cost_currency text,
    creation_time timestamp with time zone NOT NULL,
    currency text NOT NULL,
    dns_paused boolean NOT NULL,
    dns_writers text[] NOT NULL,
    drive_folder_id text,
    eap_fee_schedule public.hstore NOT NULL,
    escrow_enabled boolean NOT NULL,
    invoicing_enabled boolean NOT NULL,
    lordn_username text,
    num_dns_publish_locks integer NOT NULL,
    pending_delete_length interval NOT NULL,
    premium_list_name text,
    pricing_engine_class_name text,
    redemption_grace_period_length interval NOT NULL,
    registry_lock_or_unlock_cost_amount numeric(19,2),
    registry_lock_or_unlock_cost_currency text,
    renew_billing_cost_transitions public.hstore NOT NULL,
    renew_grace_period_length interval NOT NULL,
    reserved_list_names text[],
    restore_billing_cost_amount numeric(19,2),
    restore_billing_cost_currency text,
    roid_suffix text,
    server_status_change_billing_cost_amount numeric(19,2),
    server_status_change_billing_cost_currency text,
    tld_state_transitions public.hstore NOT NULL,
    tld_type text NOT NULL,
    tld_unicode text NOT NULL,
    transfer_grace_period_length interval NOT NULL,
    default_promo_tokens text[]
);


--
-- Name: TmchCrl; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."TmchCrl" (
    certificate_revocations text NOT NULL,
    update_timestamp timestamp with time zone NOT NULL,
    url text NOT NULL,
    id bigint DEFAULT 1 NOT NULL
);


--
-- Name: User; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."User" (
    id bigint NOT NULL,
    email_address text NOT NULL,
    gaia_id text NOT NULL,
    registry_lock_password_hash text,
    registry_lock_password_salt text,
    global_role text NOT NULL,
    is_admin boolean NOT NULL,
    registrar_roles public.hstore NOT NULL,
    update_timestamp timestamp with time zone
);


--
-- Name: User_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."User_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: User_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public."User_id_seq" OWNED BY public."User".id;


--
-- Name: project_wide_unique_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.project_wide_unique_id_seq
    START WITH 59880480006
    INCREMENT BY 1
    MINVALUE 59880480005
    NO MAXVALUE
    CACHE 10;


--
-- Name: ClaimsList revision_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ClaimsList" ALTER COLUMN revision_id SET DEFAULT nextval('public."ClaimsList_revision_id_seq"'::regclass);


--
-- Name: DomainTransactionRecord id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DomainTransactionRecord" ALTER COLUMN id SET DEFAULT nextval('public."DomainTransactionRecord_id_seq"'::regclass);


--
-- Name: PackagePromotion package_promotion_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PackagePromotion" ALTER COLUMN package_promotion_id SET DEFAULT nextval('public."Package_promotion_id_seq"'::regclass);


--
-- Name: PremiumList revision_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PremiumList" ALTER COLUMN revision_id SET DEFAULT nextval('public."PremiumList_revision_id_seq"'::regclass);


--
-- Name: RegistryLock revision_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."RegistryLock" ALTER COLUMN revision_id SET DEFAULT nextval('public."RegistryLock_revision_id_seq"'::regclass);


--
-- Name: ReservedList revision_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ReservedList" ALTER COLUMN revision_id SET DEFAULT nextval('public."ReservedList_revision_id_seq"'::regclass);


--
-- Name: SignedMarkRevocationList revision_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."SignedMarkRevocationList" ALTER COLUMN revision_id SET DEFAULT nextval('public."SignedMarkRevocationList_revision_id_seq"'::regclass);


--
-- Name: Spec11ThreatMatch id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Spec11ThreatMatch" ALTER COLUMN id SET DEFAULT nextval('public."SafeBrowsingThreat_id_seq"'::regclass);


--
-- Name: User id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."User" ALTER COLUMN id SET DEFAULT nextval('public."User_id_seq"'::regclass);


--
-- Name: AllocationToken AllocationToken_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."AllocationToken"
    ADD CONSTRAINT "AllocationToken_pkey" PRIMARY KEY (token);


--
-- Name: BillingCancellation BillingCancellation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BillingCancellation"
    ADD CONSTRAINT "BillingCancellation_pkey" PRIMARY KEY (billing_cancellation_id);


--
-- Name: BillingEvent BillingEvent_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BillingEvent"
    ADD CONSTRAINT "BillingEvent_pkey" PRIMARY KEY (billing_event_id);


--
-- Name: BillingRecurrence BillingRecurrence_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BillingRecurrence"
    ADD CONSTRAINT "BillingRecurrence_pkey" PRIMARY KEY (billing_recurrence_id);


--
-- Name: ClaimsEntry ClaimsEntry_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ClaimsEntry"
    ADD CONSTRAINT "ClaimsEntry_pkey" PRIMARY KEY (revision_id, domain_label);


--
-- Name: ClaimsList ClaimsList_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ClaimsList"
    ADD CONSTRAINT "ClaimsList_pkey" PRIMARY KEY (revision_id);


--
-- Name: ContactHistory ContactHistory_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ContactHistory"
    ADD CONSTRAINT "ContactHistory_pkey" PRIMARY KEY (contact_repo_id, history_revision_id);


--
-- Name: Contact Contact_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Contact"
    ADD CONSTRAINT "Contact_pkey" PRIMARY KEY (repo_id);


--
-- Name: Cursor Cursor_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Cursor"
    ADD CONSTRAINT "Cursor_pkey" PRIMARY KEY (scope, type);


--
-- Name: DatabaseMigrationStateSchedule DatabaseMigrationStateSchedule_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DatabaseMigrationStateSchedule"
    ADD CONSTRAINT "DatabaseMigrationStateSchedule_pkey" PRIMARY KEY (id);


--
-- Name: DelegationSignerData DelegationSignerData_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DelegationSignerData"
    ADD CONSTRAINT "DelegationSignerData_pkey" PRIMARY KEY (domain_repo_id, key_tag, algorithm, digest_type, digest);


--
-- Name: DomainDsDataHistory DomainDsDataHistory_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DomainDsDataHistory"
    ADD CONSTRAINT "DomainDsDataHistory_pkey" PRIMARY KEY (ds_data_history_revision_id);


--
-- Name: DomainHistory DomainHistory_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DomainHistory"
    ADD CONSTRAINT "DomainHistory_pkey" PRIMARY KEY (domain_repo_id, history_revision_id);


--
-- Name: DomainTransactionRecord DomainTransactionRecord_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DomainTransactionRecord"
    ADD CONSTRAINT "DomainTransactionRecord_pkey" PRIMARY KEY (id);


--
-- Name: Domain Domain_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT "Domain_pkey" PRIMARY KEY (repo_id);


--
-- Name: GracePeriodHistory GracePeriodHistory_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."GracePeriodHistory"
    ADD CONSTRAINT "GracePeriodHistory_pkey" PRIMARY KEY (grace_period_history_revision_id);


--
-- Name: GracePeriod GracePeriod_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."GracePeriod"
    ADD CONSTRAINT "GracePeriod_pkey" PRIMARY KEY (grace_period_id);


--
-- Name: HostHistory HostHistory_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."HostHistory"
    ADD CONSTRAINT "HostHistory_pkey" PRIMARY KEY (host_repo_id, history_revision_id);


--
-- Name: Host Host_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Host"
    ADD CONSTRAINT "Host_pkey" PRIMARY KEY (repo_id);


--
-- Name: Lock Lock_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Lock"
    ADD CONSTRAINT "Lock_pkey" PRIMARY KEY (resource_name, scope);


--
-- Name: PackagePromotion PackagePromotion_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PackagePromotion"
    ADD CONSTRAINT "PackagePromotion_pkey" PRIMARY KEY (package_promotion_id);


--
-- Name: PollMessage PollMessage_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PollMessage"
    ADD CONSTRAINT "PollMessage_pkey" PRIMARY KEY (poll_message_id);


--
-- Name: PremiumEntry PremiumEntry_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PremiumEntry"
    ADD CONSTRAINT "PremiumEntry_pkey" PRIMARY KEY (revision_id, domain_label);


--
-- Name: PremiumList PremiumList_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PremiumList"
    ADD CONSTRAINT "PremiumList_pkey" PRIMARY KEY (revision_id);


--
-- Name: RdeRevision RdeRevision_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."RdeRevision"
    ADD CONSTRAINT "RdeRevision_pkey" PRIMARY KEY (tld, mode, date);


--
-- Name: RegistrarPoc RegistrarPoc_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."RegistrarPoc"
    ADD CONSTRAINT "RegistrarPoc_pkey" PRIMARY KEY (registrar_id, email_address);


--
-- Name: Registrar Registrar_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Registrar"
    ADD CONSTRAINT "Registrar_pkey" PRIMARY KEY (registrar_id);


--
-- Name: RegistryLock RegistryLock_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."RegistryLock"
    ADD CONSTRAINT "RegistryLock_pkey" PRIMARY KEY (revision_id);


--
-- Name: ReservedEntry ReservedEntry_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ReservedEntry"
    ADD CONSTRAINT "ReservedEntry_pkey" PRIMARY KEY (revision_id, domain_label);


--
-- Name: ReservedList ReservedList_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ReservedList"
    ADD CONSTRAINT "ReservedList_pkey" PRIMARY KEY (revision_id);


--
-- Name: Spec11ThreatMatch SafeBrowsingThreat_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Spec11ThreatMatch"
    ADD CONSTRAINT "SafeBrowsingThreat_pkey" PRIMARY KEY (id);


--
-- Name: ServerSecret ServerSecret_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ServerSecret"
    ADD CONSTRAINT "ServerSecret_pkey" PRIMARY KEY (id);


--
-- Name: SignedMarkRevocationEntry SignedMarkRevocationEntry_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."SignedMarkRevocationEntry"
    ADD CONSTRAINT "SignedMarkRevocationEntry_pkey" PRIMARY KEY (revision_id, smd_id);


--
-- Name: SignedMarkRevocationList SignedMarkRevocationList_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."SignedMarkRevocationList"
    ADD CONSTRAINT "SignedMarkRevocationList_pkey" PRIMARY KEY (revision_id);


--
-- Name: Tld Tld_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Tld"
    ADD CONSTRAINT "Tld_pkey" PRIMARY KEY (tld_name);


--
-- Name: TmchCrl TmchCrl_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."TmchCrl"
    ADD CONSTRAINT "TmchCrl_pkey" PRIMARY KEY (id);


--
-- Name: User User_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."User"
    ADD CONSTRAINT "User_pkey" PRIMARY KEY (id);


--
-- Name: RegistryLock idx_registry_lock_repo_id_revision_id; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."RegistryLock"
    ADD CONSTRAINT idx_registry_lock_repo_id_revision_id UNIQUE (repo_id, revision_id);


--
-- Name: DomainHost ukat9erbh52e4lg3jw6ai9wkjj9; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DomainHost"
    ADD CONSTRAINT ukat9erbh52e4lg3jw6ai9wkjj9 UNIQUE (domain_repo_id, host_repo_id);


--
-- Name: DomainHistoryHost ukt2e7ae3t8gcsxd13wjx2ka7ij; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DomainHistoryHost"
    ADD CONSTRAINT ukt2e7ae3t8gcsxd13wjx2ka7ij UNIQUE (domain_history_history_revision_id, domain_history_domain_repo_id, host_repo_id);


--
-- Name: User user_unique_email; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."User"
    ADD CONSTRAINT user_unique_email UNIQUE (email_address);


--
-- Name: allocation_token_domain_name_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX allocation_token_domain_name_idx ON public."AllocationToken" USING btree (domain_name);


--
-- Name: database_migration_state_schedule_singleton; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX database_migration_state_schedule_singleton ON public."DatabaseMigrationStateSchedule" USING btree ((true));


--
-- Name: domain_dns_refresh_request_time_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX domain_dns_refresh_request_time_idx ON public."Domain" USING btree (dns_refresh_request_time);


--
-- Name: domain_history_to_ds_data_history_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX domain_history_to_ds_data_history_idx ON public."DomainDsDataHistory" USING btree (domain_repo_id, domain_history_revision_id);


--
-- Name: domain_history_to_transaction_record_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX domain_history_to_transaction_record_idx ON public."DomainTransactionRecord" USING btree (domain_repo_id, history_revision_id);


--
-- Name: idx1iy7njgb7wjmj9piml4l2g0qi; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx1iy7njgb7wjmj9piml4l2g0qi ON public."HostHistory" USING btree (history_registrar_id);


--
-- Name: idx1p3esngcwwu6hstyua6itn6ff; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx1p3esngcwwu6hstyua6itn6ff ON public."Contact" USING btree (search_name);


--
-- Name: idx1rcgkdd777bpvj0r94sltwd5y; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx1rcgkdd777bpvj0r94sltwd5y ON public."Domain" USING btree (domain_name);


--
-- Name: idx2exdfbx6oiiwnhr8j6gjpqt2j; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx2exdfbx6oiiwnhr8j6gjpqt2j ON public."BillingCancellation" USING btree (event_time);


--
-- Name: idx3y3k7m2bkgahm9sixiohgyrga; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx3y3k7m2bkgahm9sixiohgyrga ON public."Domain" USING btree (transfer_billing_event_id);


--
-- Name: idx3y752kr9uh4kh6uig54vemx0l; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx3y752kr9uh4kh6uig54vemx0l ON public."Contact" USING btree (creation_time);


--
-- Name: idx4ytbe5f3b39trsd4okx5ijhs4; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx4ytbe5f3b39trsd4okx5ijhs4 ON public."BillingCancellation" USING btree (billing_event_id);


--
-- Name: idx5mnf0wn20tno4b9do88j61klr; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx5mnf0wn20tno4b9do88j61klr ON public."Domain" USING btree (deletion_time);


--
-- Name: idx5u5m6clpk3nktrvtyy5umacb6; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx5u5m6clpk3nktrvtyy5umacb6 ON public."GracePeriod" USING btree (billing_recurrence_id);


--
-- Name: idx5yfbr88439pxw0v3j86c74fp8; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx5yfbr88439pxw0v3j86c74fp8 ON public."BillingEvent" USING btree (event_time);


--
-- Name: idx67qwkjtlq5q8dv6egtrtnhqi7; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx67qwkjtlq5q8dv6egtrtnhqi7 ON public."HostHistory" USING btree (history_modification_time);


--
-- Name: idx6ebt3nwk5ocvnremnhnlkl6ff; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx6ebt3nwk5ocvnremnhnlkl6ff ON public."BillingEvent" USING btree (cancellation_matching_billing_recurrence_id);


--
-- Name: idx6py6ocrab0ivr76srcd2okpnq; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx6py6ocrab0ivr76srcd2okpnq ON public."BillingEvent" USING btree (billing_time);


--
-- Name: idx6syykou4nkc7hqa5p8r92cpch; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx6syykou4nkc7hqa5p8r92cpch ON public."BillingRecurrence" USING btree (event_time);


--
-- Name: idx6w3qbtgce93cal2orjg1tw7b7; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx6w3qbtgce93cal2orjg1tw7b7 ON public."DomainHistory" USING btree (history_modification_time);


--
-- Name: idx73l103vc5900ig3p4odf0cngt; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx73l103vc5900ig3p4odf0cngt ON public."BillingEvent" USING btree (registrar_id);


--
-- Name: idx8nr0ke9mrrx4ewj6pd2ag4rmr; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx8nr0ke9mrrx4ewj6pd2ag4rmr ON public."Domain" USING btree (creation_time);


--
-- Name: idx9g3s7mjv1yn4t06nqid39whss; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx9g3s7mjv1yn4t06nqid39whss ON public."AllocationToken" USING btree (token_type);


--
-- Name: idx9q53px6r302ftgisqifmc6put; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx9q53px6r302ftgisqifmc6put ON public."ContactHistory" USING btree (history_type);


--
-- Name: idx_registry_lock_registrar_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_registry_lock_registrar_id ON public."RegistryLock" USING btree (registrar_id);


--
-- Name: idx_registry_lock_verification_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_registry_lock_verification_code ON public."RegistryLock" USING btree (verification_code);


--
-- Name: idxa7fu0bqynfb79rr80528b4jqt; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxa7fu0bqynfb79rr80528b4jqt ON public."Domain" USING btree (registrant_contact);


--
-- Name: idxaro1omfuaxjwmotk3vo00trwm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxaro1omfuaxjwmotk3vo00trwm ON public."DomainHistory" USING btree (history_registrar_id);


--
-- Name: idxaydgox62uno9qx8cjlj5lauye; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxaydgox62uno9qx8cjlj5lauye ON public."PollMessage" USING btree (event_time);


--
-- Name: idxbgfmveqa7e5hn689koikwn70r; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxbgfmveqa7e5hn689koikwn70r ON public."BillingEvent" USING btree (domain_repo_id);


--
-- Name: idxbgssjudpm428mrv0xfpvgifps; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxbgssjudpm428mrv0xfpvgifps ON public."GracePeriod" USING btree (billing_event_id);


--
-- Name: idxbn8t4wp85fgxjl8q4ctlscx55; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxbn8t4wp85fgxjl8q4ctlscx55 ON public."Contact" USING btree (current_sponsor_registrar_id);


--
-- Name: idxcju58vqascbpve1t7fem53ctl; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxcju58vqascbpve1t7fem53ctl ON public."Domain" USING btree (transfer_billing_recurrence_id);


--
-- Name: idxd01j17vrpjxaerxdmn8bwxs7s; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxd01j17vrpjxaerxdmn8bwxs7s ON public."GracePeriodHistory" USING btree (domain_repo_id);


--
-- Name: idxe7wu46c7wpvfmfnj4565abibp; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxe7wu46c7wpvfmfnj4565abibp ON public."PollMessage" USING btree (registrar_id);


--
-- Name: idxeokttmxtpq2hohcioe5t2242b; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxeokttmxtpq2hohcioe5t2242b ON public."BillingCancellation" USING btree (registrar_id);


--
-- Name: idxfg2nnjlujxo6cb9fha971bq2n; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxfg2nnjlujxo6cb9fha971bq2n ON public."HostHistory" USING btree (creation_time);


--
-- Name: idxhlqqd5uy98cjyos72d81x9j95; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxhlqqd5uy98cjyos72d81x9j95 ON public."DelegationSignerData" USING btree (domain_repo_id);


--
-- Name: idxhmv411mdqo5ibn4vy7ykxpmlv; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxhmv411mdqo5ibn4vy7ykxpmlv ON public."BillingEvent" USING btree (allocation_token);


--
-- Name: idxhp33wybmb6tbpr1bq7ttwk8je; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxhp33wybmb6tbpr1bq7ttwk8je ON public."ContactHistory" USING btree (history_registrar_id);


--
-- Name: idxj1mtx98ndgbtb1bkekahms18w; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxj1mtx98ndgbtb1bkekahms18w ON public."GracePeriod" USING btree (domain_repo_id);


--
-- Name: idxj77pfwhui9f0i7wjq6lmibovj; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxj77pfwhui9f0i7wjq6lmibovj ON public."HostHistory" USING btree (host_name);


--
-- Name: idxjny8wuot75b5e6p38r47wdawu; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxjny8wuot75b5e6p38r47wdawu ON public."BillingRecurrence" USING btree (recurrence_time_of_year);


--
-- Name: idxjw3rwtfrexyq53x9vu7qghrdt; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxjw3rwtfrexyq53x9vu7qghrdt ON public."DomainHost" USING btree (host_repo_id);


--
-- Name: idxkjt9yaq92876dstimd93hwckh; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxkjt9yaq92876dstimd93hwckh ON public."Domain" USING btree (current_sponsor_registrar_id);


--
-- Name: idxknk8gmj7s47q56cwpa6rmpt5l; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxknk8gmj7s47q56cwpa6rmpt5l ON public."HostHistory" USING btree (history_type);


--
-- Name: idxkpkh68n6dy5v51047yr6b0e9l; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxkpkh68n6dy5v51047yr6b0e9l ON public."Host" USING btree (host_name);


--
-- Name: idxku0fopwyvd57ebo8bf0jg9xo2; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxku0fopwyvd57ebo8bf0jg9xo2 ON public."BillingCancellation" USING btree (billing_recurrence_id);


--
-- Name: idxl49vydnq0h5j1piefwjy4i8er; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxl49vydnq0h5j1piefwjy4i8er ON public."Host" USING btree (current_sponsor_registrar_id);


--
-- Name: idxl8vobbecsd32k4ksavdfx8st6; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxl8vobbecsd32k4ksavdfx8st6 ON public."BillingCancellation" USING btree (domain_repo_id);


--
-- Name: idxlg6a5tp70nch9cp0gc11brc5o; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxlg6a5tp70nch9cp0gc11brc5o ON public."PackagePromotion" USING btree (token);


--
-- Name: idxlrq7v63pc21uoh3auq6eybyhl; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxlrq7v63pc21uoh3auq6eybyhl ON public."Domain" USING btree (autorenew_end_time);


--
-- Name: idxn1f711wicdnooa2mqb7g1m55o; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxn1f711wicdnooa2mqb7g1m55o ON public."Contact" USING btree (deletion_time);


--
-- Name: idxn898pb9mwcg359cdwvolb11ck; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxn898pb9mwcg359cdwvolb11ck ON public."BillingRecurrence" USING btree (registrar_id);


--
-- Name: idxnb02m43jcx24r64n8rbg22u4q; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxnb02m43jcx24r64n8rbg22u4q ON public."Domain" USING btree (admin_contact);


--
-- Name: idxo1xdtpij2yryh0skxe9v91sep; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxo1xdtpij2yryh0skxe9v91sep ON public."ContactHistory" USING btree (creation_time);


--
-- Name: idxoqd7n4hbx86hvlgkilq75olas; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxoqd7n4hbx86hvlgkilq75olas ON public."Contact" USING btree (contact_id);


--
-- Name: idxoqttafcywwdn41um6kwlt0n8b; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxoqttafcywwdn41um6kwlt0n8b ON public."BillingRecurrence" USING btree (domain_repo_id);


--
-- Name: idxovmntef6l45tw2bsfl56tcugx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxovmntef6l45tw2bsfl56tcugx ON public."Host" USING btree (deletion_time);


--
-- Name: idxp0pxi708hlu4n40qhbtihge8x; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxp0pxi708hlu4n40qhbtihge8x ON public."BillingRecurrence" USING btree (recurrence_last_expansion);


--
-- Name: idxp3usbtvk0v1m14i5tdp4xnxgc; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxp3usbtvk0v1m14i5tdp4xnxgc ON public."BillingRecurrence" USING btree (recurrence_end_time);


--
-- Name: idxplxf9v56p0wg8ws6qsvd082hk; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxplxf9v56p0wg8ws6qsvd082hk ON public."BillingEvent" USING btree (synthetic_creation_time);


--
-- Name: idxq9gy8x2xynt9tb16yajn1gcm8; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxq9gy8x2xynt9tb16yajn1gcm8 ON public."Domain" USING btree (billing_contact);


--
-- Name: idxqa3g92jc17e8dtiaviy4fet4x; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxqa3g92jc17e8dtiaviy4fet4x ON public."BillingCancellation" USING btree (billing_time);


--
-- Name: idxr22ciyccwi9rrqmt1ro0s59qf; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxr22ciyccwi9rrqmt1ro0s59qf ON public."Domain" USING btree (tech_contact);


--
-- Name: idxrc77s1ndiemi2vwwudchye214; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxrc77s1ndiemi2vwwudchye214 ON public."Host" USING gin (inet_addresses);


--
-- Name: idxrh4xmrot9bd63o382ow9ltfig; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxrh4xmrot9bd63o382ow9ltfig ON public."DomainHistory" USING btree (creation_time);


--
-- Name: idxrwl38wwkli1j7gkvtywi9jokq; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxrwl38wwkli1j7gkvtywi9jokq ON public."Domain" USING btree (tld);


--
-- Name: idxsfci08jgsymxy6ovh4k7r358c; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxsfci08jgsymxy6ovh4k7r358c ON public."Domain" USING btree (billing_recurrence_id);


--
-- Name: idxsu1nam10cjes9keobapn5jvxj; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxsu1nam10cjes9keobapn5jvxj ON public."DomainHistory" USING btree (history_type);


--
-- Name: idxsudwswtwqnfnx2o1hx4s0k0g5; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxsudwswtwqnfnx2o1hx4s0k0g5 ON public."ContactHistory" USING btree (history_modification_time);


--
-- Name: idxtmlqd31dpvvd2g1h9i7erw6aj; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxtmlqd31dpvvd2g1h9i7erw6aj ON public."AllocationToken" USING btree (redemption_domain_repo_id);


--
-- Name: idxy98mebut8ix1v07fjxxdkqcx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idxy98mebut8ix1v07fjxxdkqcx ON public."Host" USING btree (creation_time);


--
-- Name: premiumlist_name_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX premiumlist_name_idx ON public."PremiumList" USING btree (name);


--
-- Name: registrar_iana_identifier_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX registrar_iana_identifier_idx ON public."Registrar" USING btree (iana_identifier);


--
-- Name: registrar_name_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX registrar_name_idx ON public."Registrar" USING btree (registrar_name);


--
-- Name: registrarpoc_gae_user_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX registrarpoc_gae_user_id_idx ON public."RegistrarPoc" USING btree (gae_user_id);


--
-- Name: registrarpoc_login_email_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX registrarpoc_login_email_idx ON public."RegistrarPoc" USING btree (login_email_address);


--
-- Name: reservedlist_name_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX reservedlist_name_idx ON public."ReservedList" USING btree (name);


--
-- Name: spec11threatmatch_check_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX spec11threatmatch_check_date_idx ON public."Spec11ThreatMatch" USING btree (check_date);


--
-- Name: spec11threatmatch_registrar_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX spec11threatmatch_registrar_id_idx ON public."Spec11ThreatMatch" USING btree (registrar_id);


--
-- Name: spec11threatmatch_tld_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX spec11threatmatch_tld_idx ON public."Spec11ThreatMatch" USING btree (tld);


--
-- Name: user_email_address_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX user_email_address_idx ON public."User" USING btree (email_address);


--
-- Name: user_gaia_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX user_gaia_id_idx ON public."User" USING btree (gaia_id);


--
-- Name: Contact fk1sfyj7o7954prbn1exk7lpnoe; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Contact"
    ADD CONSTRAINT fk1sfyj7o7954prbn1exk7lpnoe FOREIGN KEY (creation_registrar_id) REFERENCES public."Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: Domain fk2jc69qyg2tv9hhnmif6oa1cx1; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fk2jc69qyg2tv9hhnmif6oa1cx1 FOREIGN KEY (creation_registrar_id) REFERENCES public."Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: RegistryLock fk2lhcwpxlnqijr96irylrh1707; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."RegistryLock"
    ADD CONSTRAINT fk2lhcwpxlnqijr96irylrh1707 FOREIGN KEY (relock_revision_id) REFERENCES public."RegistryLock"(revision_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: Domain fk2u3srsfbei272093m3b3xwj23; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fk2u3srsfbei272093m3b3xwj23 FOREIGN KEY (current_sponsor_registrar_id) REFERENCES public."Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: SignedMarkRevocationEntry fk5ivlhvs3121yx2li5tqh54u4; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."SignedMarkRevocationEntry"
    ADD CONSTRAINT fk5ivlhvs3121yx2li5tqh54u4 FOREIGN KEY (revision_id) REFERENCES public."SignedMarkRevocationList"(revision_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: ClaimsEntry fk6sc6at5hedffc0nhdcab6ivuq; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ClaimsEntry"
    ADD CONSTRAINT fk6sc6at5hedffc0nhdcab6ivuq FOREIGN KEY (revision_id) REFERENCES public."ClaimsList"(revision_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: GracePeriodHistory fk7w3cx8d55q8bln80e716tr7b8; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."GracePeriodHistory"
    ADD CONSTRAINT fk7w3cx8d55q8bln80e716tr7b8 FOREIGN KEY (domain_repo_id, domain_history_revision_id) REFERENCES public."DomainHistory"(domain_repo_id, history_revision_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: Contact fk93c185fx7chn68uv7nl6uv2s0; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Contact"
    ADD CONSTRAINT fk93c185fx7chn68uv7nl6uv2s0 FOREIGN KEY (current_sponsor_registrar_id) REFERENCES public."Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: BillingCancellation fk_billing_cancellation_billing_event_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BillingCancellation"
    ADD CONSTRAINT fk_billing_cancellation_billing_event_id FOREIGN KEY (billing_event_id) REFERENCES public."BillingEvent"(billing_event_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: BillingCancellation fk_billing_cancellation_billing_recurrence_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BillingCancellation"
    ADD CONSTRAINT fk_billing_cancellation_billing_recurrence_id FOREIGN KEY (billing_recurrence_id) REFERENCES public."BillingRecurrence"(billing_recurrence_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: BillingCancellation fk_billing_cancellation_domain_history; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BillingCancellation"
    ADD CONSTRAINT fk_billing_cancellation_domain_history FOREIGN KEY (domain_repo_id, domain_history_revision_id) REFERENCES public."DomainHistory"(domain_repo_id, history_revision_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: BillingCancellation fk_billing_cancellation_registrar_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BillingCancellation"
    ADD CONSTRAINT fk_billing_cancellation_registrar_id FOREIGN KEY (registrar_id) REFERENCES public."Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: BillingEvent fk_billing_event_allocation_token; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BillingEvent"
    ADD CONSTRAINT fk_billing_event_allocation_token FOREIGN KEY (allocation_token) REFERENCES public."AllocationToken"(token) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: BillingEvent fk_billing_event_cancellation_matching_billing_recurrence_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BillingEvent"
    ADD CONSTRAINT fk_billing_event_cancellation_matching_billing_recurrence_id FOREIGN KEY (cancellation_matching_billing_recurrence_id) REFERENCES public."BillingRecurrence"(billing_recurrence_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: BillingEvent fk_billing_event_domain_history; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BillingEvent"
    ADD CONSTRAINT fk_billing_event_domain_history FOREIGN KEY (domain_repo_id, domain_history_revision_id) REFERENCES public."DomainHistory"(domain_repo_id, history_revision_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: BillingEvent fk_billing_event_recurrence_history; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BillingEvent"
    ADD CONSTRAINT fk_billing_event_recurrence_history FOREIGN KEY (domain_repo_id, recurrence_history_revision_id) REFERENCES public."DomainHistory"(domain_repo_id, history_revision_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: BillingEvent fk_billing_event_registrar_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BillingEvent"
    ADD CONSTRAINT fk_billing_event_registrar_id FOREIGN KEY (registrar_id) REFERENCES public."Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: BillingRecurrence fk_billing_recurrence_domain_history; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BillingRecurrence"
    ADD CONSTRAINT fk_billing_recurrence_domain_history FOREIGN KEY (domain_repo_id, domain_history_revision_id) REFERENCES public."DomainHistory"(domain_repo_id, history_revision_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: BillingRecurrence fk_billing_recurrence_registrar_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BillingRecurrence"
    ADD CONSTRAINT fk_billing_recurrence_registrar_id FOREIGN KEY (registrar_id) REFERENCES public."Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: ContactHistory fk_contact_history_contact_repo_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ContactHistory"
    ADD CONSTRAINT fk_contact_history_contact_repo_id FOREIGN KEY (contact_repo_id) REFERENCES public."Contact"(repo_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: ContactHistory fk_contact_history_registrar_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ContactHistory"
    ADD CONSTRAINT fk_contact_history_registrar_id FOREIGN KEY (history_registrar_id) REFERENCES public."Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: Contact fk_contact_transfer_gaining_registrar_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Contact"
    ADD CONSTRAINT fk_contact_transfer_gaining_registrar_id FOREIGN KEY (transfer_gaining_registrar_id) REFERENCES public."Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: Contact fk_contact_transfer_losing_registrar_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Contact"
    ADD CONSTRAINT fk_contact_transfer_losing_registrar_id FOREIGN KEY (transfer_losing_registrar_id) REFERENCES public."Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: Domain fk_domain_admin_contact; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fk_domain_admin_contact FOREIGN KEY (admin_contact) REFERENCES public."Contact"(repo_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: Domain fk_domain_billing_contact; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fk_domain_billing_contact FOREIGN KEY (billing_contact) REFERENCES public."Contact"(repo_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: Domain fk_domain_billing_recurrence_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fk_domain_billing_recurrence_id FOREIGN KEY (billing_recurrence_id) REFERENCES public."BillingRecurrence"(billing_recurrence_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: Domain fk_domain_current_package_token; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fk_domain_current_package_token FOREIGN KEY (current_package_token) REFERENCES public."AllocationToken"(token) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: DomainHistory fk_domain_history_current_package_token; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DomainHistory"
    ADD CONSTRAINT fk_domain_history_current_package_token FOREIGN KEY (current_package_token) REFERENCES public."AllocationToken"(token) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: DomainHistory fk_domain_history_domain_repo_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DomainHistory"
    ADD CONSTRAINT fk_domain_history_domain_repo_id FOREIGN KEY (domain_repo_id) REFERENCES public."Domain"(repo_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: DomainHistory fk_domain_history_registrar_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DomainHistory"
    ADD CONSTRAINT fk_domain_history_registrar_id FOREIGN KEY (history_registrar_id) REFERENCES public."Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: Domain fk_domain_registrant_contact; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fk_domain_registrant_contact FOREIGN KEY (registrant_contact) REFERENCES public."Contact"(repo_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: Domain fk_domain_tech_contact; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fk_domain_tech_contact FOREIGN KEY (tech_contact) REFERENCES public."Contact"(repo_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: Domain fk_domain_tld; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fk_domain_tld FOREIGN KEY (tld) REFERENCES public."Tld"(tld_name) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: DomainTransactionRecord fk_domain_transaction_record_tld; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DomainTransactionRecord"
    ADD CONSTRAINT fk_domain_transaction_record_tld FOREIGN KEY (tld) REFERENCES public."Tld"(tld_name) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: Domain fk_domain_transfer_billing_cancellation_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fk_domain_transfer_billing_cancellation_id FOREIGN KEY (transfer_billing_cancellation_id) REFERENCES public."BillingCancellation"(billing_cancellation_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: Domain fk_domain_transfer_billing_event_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fk_domain_transfer_billing_event_id FOREIGN KEY (transfer_billing_event_id) REFERENCES public."BillingEvent"(billing_event_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: Domain fk_domain_transfer_billing_recurrence_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fk_domain_transfer_billing_recurrence_id FOREIGN KEY (transfer_billing_recurrence_id) REFERENCES public."BillingRecurrence"(billing_recurrence_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: Domain fk_domain_transfer_gaining_registrar_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fk_domain_transfer_gaining_registrar_id FOREIGN KEY (transfer_gaining_registrar_id) REFERENCES public."Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: Domain fk_domain_transfer_losing_registrar_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fk_domain_transfer_losing_registrar_id FOREIGN KEY (transfer_losing_registrar_id) REFERENCES public."Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: DomainHost fk_domainhost_host_valid; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DomainHost"
    ADD CONSTRAINT fk_domainhost_host_valid FOREIGN KEY (host_repo_id) REFERENCES public."Host"(repo_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: GracePeriod fk_grace_period_billing_event_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."GracePeriod"
    ADD CONSTRAINT fk_grace_period_billing_event_id FOREIGN KEY (billing_event_id) REFERENCES public."BillingEvent"(billing_event_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: GracePeriod fk_grace_period_billing_recurrence_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."GracePeriod"
    ADD CONSTRAINT fk_grace_period_billing_recurrence_id FOREIGN KEY (billing_recurrence_id) REFERENCES public."BillingRecurrence"(billing_recurrence_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: GracePeriod fk_grace_period_domain_repo_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."GracePeriod"
    ADD CONSTRAINT fk_grace_period_domain_repo_id FOREIGN KEY (domain_repo_id) REFERENCES public."Domain"(repo_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: GracePeriod fk_grace_period_registrar_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."GracePeriod"
    ADD CONSTRAINT fk_grace_period_registrar_id FOREIGN KEY (registrar_id) REFERENCES public."Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: HostHistory fk_history_registrar_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."HostHistory"
    ADD CONSTRAINT fk_history_registrar_id FOREIGN KEY (history_registrar_id) REFERENCES public."Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: Host fk_host_creation_registrar_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Host"
    ADD CONSTRAINT fk_host_creation_registrar_id FOREIGN KEY (creation_registrar_id) REFERENCES public."Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: Host fk_host_current_sponsor_registrar_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Host"
    ADD CONSTRAINT fk_host_current_sponsor_registrar_id FOREIGN KEY (current_sponsor_registrar_id) REFERENCES public."Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: Host fk_host_last_epp_update_registrar_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Host"
    ADD CONSTRAINT fk_host_last_epp_update_registrar_id FOREIGN KEY (last_epp_update_registrar_id) REFERENCES public."Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: Host fk_host_superordinate_domain; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Host"
    ADD CONSTRAINT fk_host_superordinate_domain FOREIGN KEY (superordinate_domain) REFERENCES public."Domain"(repo_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: HostHistory fk_hosthistory_host; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."HostHistory"
    ADD CONSTRAINT fk_hosthistory_host FOREIGN KEY (host_repo_id) REFERENCES public."Host"(repo_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: PollMessage fk_poll_message_contact_history; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PollMessage"
    ADD CONSTRAINT fk_poll_message_contact_history FOREIGN KEY (contact_repo_id, contact_history_revision_id) REFERENCES public."ContactHistory"(contact_repo_id, history_revision_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: PollMessage fk_poll_message_contact_repo_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PollMessage"
    ADD CONSTRAINT fk_poll_message_contact_repo_id FOREIGN KEY (contact_repo_id) REFERENCES public."Contact"(repo_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: PollMessage fk_poll_message_domain_history; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PollMessage"
    ADD CONSTRAINT fk_poll_message_domain_history FOREIGN KEY (domain_repo_id, domain_history_revision_id) REFERENCES public."DomainHistory"(domain_repo_id, history_revision_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: PollMessage fk_poll_message_domain_repo_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PollMessage"
    ADD CONSTRAINT fk_poll_message_domain_repo_id FOREIGN KEY (domain_repo_id) REFERENCES public."Domain"(repo_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: PollMessage fk_poll_message_host_history; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PollMessage"
    ADD CONSTRAINT fk_poll_message_host_history FOREIGN KEY (host_repo_id, host_history_revision_id) REFERENCES public."HostHistory"(host_repo_id, history_revision_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: PollMessage fk_poll_message_host_repo_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PollMessage"
    ADD CONSTRAINT fk_poll_message_host_repo_id FOREIGN KEY (host_repo_id) REFERENCES public."Host"(repo_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: PollMessage fk_poll_message_registrar_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PollMessage"
    ADD CONSTRAINT fk_poll_message_registrar_id FOREIGN KEY (registrar_id) REFERENCES public."Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: PollMessage fk_poll_message_transfer_response_gaining_registrar_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PollMessage"
    ADD CONSTRAINT fk_poll_message_transfer_response_gaining_registrar_id FOREIGN KEY (transfer_response_gaining_registrar_id) REFERENCES public."Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: PollMessage fk_poll_message_transfer_response_losing_registrar_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PollMessage"
    ADD CONSTRAINT fk_poll_message_transfer_response_losing_registrar_id FOREIGN KEY (transfer_response_losing_registrar_id) REFERENCES public."Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: RegistrarPoc fk_registrar_poc_registrar_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."RegistrarPoc"
    ADD CONSTRAINT fk_registrar_poc_registrar_id FOREIGN KEY (registrar_id) REFERENCES public."Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: DomainHistoryHost fka9woh3hu8gx5x0vly6bai327n; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DomainHistoryHost"
    ADD CONSTRAINT fka9woh3hu8gx5x0vly6bai327n FOREIGN KEY (domain_history_domain_repo_id, domain_history_history_revision_id) REFERENCES public."DomainHistory"(domain_repo_id, history_revision_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: DomainTransactionRecord fkcjqe54u72kha71vkibvxhjye7; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DomainTransactionRecord"
    ADD CONSTRAINT fkcjqe54u72kha71vkibvxhjye7 FOREIGN KEY (domain_repo_id, history_revision_id) REFERENCES public."DomainHistory"(domain_repo_id, history_revision_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: DomainHost fkfmi7bdink53swivs390m2btxg; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DomainHost"
    ADD CONSTRAINT fkfmi7bdink53swivs390m2btxg FOREIGN KEY (domain_repo_id) REFERENCES public."Domain"(repo_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: ReservedEntry fkgq03rk0bt1hb915dnyvd3vnfc; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ReservedEntry"
    ADD CONSTRAINT fkgq03rk0bt1hb915dnyvd3vnfc FOREIGN KEY (revision_id) REFERENCES public."ReservedList"(revision_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: Domain fkjc0r9r5y1lfbt4gpbqw4wsuvq; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT fkjc0r9r5y1lfbt4gpbqw4wsuvq FOREIGN KEY (last_epp_update_registrar_id) REFERENCES public."Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: Contact fkmb7tdiv85863134w1wogtxrb2; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Contact"
    ADD CONSTRAINT fkmb7tdiv85863134w1wogtxrb2 FOREIGN KEY (last_epp_update_registrar_id) REFERENCES public."Registrar"(registrar_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: PremiumEntry fko0gw90lpo1tuee56l0nb6y6g5; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PremiumEntry"
    ADD CONSTRAINT fko0gw90lpo1tuee56l0nb6y6g5 FOREIGN KEY (revision_id) REFERENCES public."PremiumList"(revision_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: DomainDsDataHistory fko4ilgyyfnvppbpuivus565i0j; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DomainDsDataHistory"
    ADD CONSTRAINT fko4ilgyyfnvppbpuivus565i0j FOREIGN KEY (domain_repo_id, domain_history_revision_id) REFERENCES public."DomainHistory"(domain_repo_id, history_revision_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: DelegationSignerData fktr24j9v14ph2mfuw2gsmt12kq; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DelegationSignerData"
    ADD CONSTRAINT fktr24j9v14ph2mfuw2gsmt12kq FOREIGN KEY (domain_repo_id) REFERENCES public."Domain"(repo_id) DEFERRABLE INITIALLY DEFERRED;


--
-- PostgreSQL database dump complete
--

