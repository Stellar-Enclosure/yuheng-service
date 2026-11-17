-- 20261116_002 yuheng llm routing_mode & cache_policy
ALTER TABLE public.gateway_llm_model_t0
    ADD COLUMN routing_mode varchar(32) NOT NULL DEFAULT 'PRIORITY_WEIGHT'
        CHECK (routing_mode IN ('PRIORITY_WEIGHT', 'PREFIX_AFFINITY')),
    ADD COLUMN cache_policy jsonb NOT NULL DEFAULT '{"mode":"NONE"}'::jsonb;
