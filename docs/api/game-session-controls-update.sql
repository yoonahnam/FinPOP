-- game-turn-update.sql 적용 후 실행합니다. 기존 계정·캐릭터·문항 데이터는 유지합니다.
-- 재실행해도 게임 행을 초기화하거나 기존 함수의 권한을 공개하지 않습니다.
begin;
alter table public.game_sessions add column if not exists paused_at timestamptz;
alter table public.game_sessions add column if not exists abandoned_at timestamptz;
create or replace function finpop_private.game_snapshot(p_game_id uuid) returns jsonb
language sql stable security invoker set search_path='' as $$
select jsonb_build_object(
 'gameId',g.id,'mode',g.mode,'status',g.status,'phase',g.phase,
 'stateVersion',g.state_version,'rulesVersion',g.rules_version,'quizStage',g.quiz_stage,
 'pausedAt',g.paused_at,'abandonedAt',g.abandoned_at,
 'pausedPhase',case when g.status='PAUSED' then g.phase else null end,
 'remainingQuizMs',(select remaining_millis from public.game_quiz_attempts where game_id=g.id and resolved_at is null and g.status='PAUSED'),
 'rewardGranted',false,'completedCountIncremented',false,
 'currentPlayerId',g.current_player_id,'currentTurnId',g.current_turn_id,
 'players',(select coalesce(jsonb_agg(jsonb_build_object(
   'playerId',p.player_id,'isAi',p.is_ai,'characterId',p.character_id,
   'characterLevel',p.character_level,'position',p.position,'gameGold',p.game_gold,
   'remainingTurns',p.remaining_turns,'islandWaitTurns',p.island_wait_turns,
   'abilityState',p.ability_state) order by p.player_id),'[]'::jsonb)
   from public.game_players p where p.game_id=g.id),
 'tiles',(select coalesce(jsonb_agg(jsonb_build_object('tileId',t.tile_id,
   'ownerPlayerId',t.owner_player_id) order by t.tile_id),'[]'::jsonb)
   from public.game_tiles t where t.game_id=g.id),
 'pendingQuiz',(select jsonb_build_object('attemptId',q.id,'playerId',q.player_id,'questionVersionId',q.question_version_id,
   'tileId',q.tile_id,'topic',q.topic,'sourceType',q.source_type,'stage',q.stage,
   'question',q.question,'hint',q.hint,'choices',q.choices,'issuedAt',q.issued_at,
   'deadlineAt',q.deadline_at) from public.game_quiz_attempts q
   where q.game_id=g.id and q.resolved_at is null and g.status in ('ACTIVE','PAUSED')),
 'lastQuizResult',finpop_private.game_quiz_result(g.id),
 'pendingEvent',g.pending_event,'pendingPurchase',g.pending_purchase,'updatedAt',g.updated_at)
from public.game_sessions g where g.id=p_game_id;
$$;
create or replace function public.finpop_game_action(
 p_user_id uuid, p_operation text, p_game_id uuid default null,
 p_request_key uuid default null, p_body jsonb default '{}'::jsonb
) returns jsonb language plpgsql security invoker set search_path='' as $$
declare
 g public.game_sessions%rowtype;
 p public.game_players%rowtype;
 q record;
 cached finpop_private.game_action_requests%rowtype;
 v_game_id uuid := p_game_id;
 v_cache_game uuid;
 v_meta jsonb := jsonb_build_object('serverTime',clock_timestamp(),'requestId',gen_random_uuid());
 v_fingerprint jsonb := jsonb_build_object('operation',p_operation,'gameId',p_game_id,'body',p_body);
 v_data jsonb; v_envelope jsonb; v_status integer := 200; v_code text;
 v_mutation boolean := p_operation in ('create','initiative','roll','ai-roll','answer','quiz-timeout','ai-answer','purchase','ai-purchase','complete-turn','ai-complete-turn','island-skip','ai-island-skip','pause','resume','abandon');
 v_level integer; v_stage integer; v_user_level integer; v_completed integer;
 v_a integer; v_b integer; v_dice integer; v_position integer; v_bonus integer;
 v_path jsonb; v_rolls jsonb := '[]'::jsonb; v_events jsonb := '[]'::jsonb;
 v_actor text; v_first text; v_turn uuid; v_tile jsonb; v_owner text; v_topic text;
 v_now timestamptz; v_remaining integer;
 v_result jsonb; v_allowed text[]; v_attempt uuid; v_issued timestamptz; v_active uuid;
begin
 if p_user_id is null then
   return jsonb_build_object('httpStatus',401,'body',jsonb_build_object('error',
     jsonb_build_object('code','AUTH_REQUIRED','message','인증이 필요합니다.','details','{}'::jsonb),'meta',v_meta));
 end if;
 if not exists(select 1 from public.users where id=p_user_id) then
   return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',
     jsonb_build_object('code','PROFILE_UNAVAILABLE','message','계정 초기화를 먼저 완료해 주세요.','details','{}'::jsonb),'meta',v_meta));
 end if;
 -- 사용자별 생성 경쟁과 요청 키 경쟁을 같은 트랜잭션에서 직렬화합니다.
 perform pg_advisory_xact_lock(hashtextextended(p_user_id::text,0));
 if v_mutation then
   if p_request_key is null then
     return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',
       jsonb_build_object('code','INVALID_REQUEST','message','Idempotency-Key가 필요합니다.','details','{}'::jsonb),'meta',v_meta));
   end if;
   select * into cached from finpop_private.game_action_requests
     where user_id=p_user_id and request_key=p_request_key;
   if found then
     if cached.operation=p_operation and cached.request_body=v_fingerprint then
       return jsonb_build_object('httpStatus',cached.http_status,'body',cached.response_body);
     end if;
     return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',
       jsonb_build_object('code','IDEMPOTENCY_CONFLICT','message','같은 키에 다른 요청을 사용할 수 없습니다.',
       'details','{}'::jsonb),'meta',v_meta));
   end if;
 end if;

 -- 이 블록의 모든 게임 변경은 오류 발생 시 함께 롤백됩니다.
 begin
   if p_body is null or jsonb_typeof(p_body)<>'object' then perform finpop_private.game_fail('INVALID_REQUEST'); end if;
   if p_operation='config' then
     if p_body<>'{}'::jsonb or p_game_id is not null then perform finpop_private.game_fail('INVALID_REQUEST'); end if;
     v_data:=finpop_private.game_config();
   elsif p_operation='create' then
     if (p_body - array['mode','characterId'])<>'{}'::jsonb or p_body->>'mode' is distinct from 'AI'
       or jsonb_typeof(p_body->'characterId') is distinct from 'string' or p_game_id is not null then
       perform finpop_private.game_fail('INVALID_REQUEST');
     end if;
     select id into v_active from public.game_sessions where user_id=p_user_id and status in ('ACTIVE','PAUSED');
     if found then perform finpop_private.game_fail('ACTIVE_GAME_EXISTS'); end if;
     select user_level,completed_game_count into v_user_level,v_completed from public.users
       where id=p_user_id for update;
     if v_completed<0 then perform finpop_private.game_fail('PROFILE_UNAVAILABLE'); end if;
     select uc.character_level into v_level from public.user_characters uc
       join public.characters c on c.id=uc.character_id
       where uc.user_id=p_user_id and uc.character_id=p_body->>'characterId'
         and c.unlock_user_level<=v_user_level and uc.character_level between 1 and least(c.max_level,5)
       for share of uc;
     if not found then perform finpop_private.game_fail('CHARACTER_LOCKED'); end if;
     v_stage:=1+v_completed/2;
     insert into public.game_sessions(user_id,quiz_stage) values(p_user_id,v_stage) returning id into v_game_id;
     insert into public.game_players(game_id,player_id,is_ai,character_id,character_level) values
       (v_game_id,'P1',false,p_body->>'characterId',v_level),(v_game_id,'P2',true,'CHEESE',1);
     insert into public.game_tiles(game_id,tile_id) select v_game_id,generate_series(0,13);
     v_data:=finpop_private.game_snapshot(v_game_id);
   elsif p_operation in ('get','initiative','roll','ai-roll','answer','quiz-timeout','ai-answer','purchase','ai-purchase','complete-turn','ai-complete-turn','island-skip','ai-island-skip','pause','resume','abandon') then
     if p_game_id is null then perform finpop_private.game_fail('INVALID_REQUEST'); end if;
     select * into g from public.game_sessions where id=p_game_id for update;
     if not found then perform finpop_private.game_fail('RESOURCE_NOT_FOUND'); end if;
     if g.user_id<>p_user_id then perform finpop_private.game_fail('FORBIDDEN'); end if;
     v_cache_game:=g.id;
     if p_operation='get' then
       if p_body<>'{}'::jsonb then perform finpop_private.game_fail('INVALID_REQUEST'); end if;
       v_data:=finpop_private.game_snapshot(g.id);
     else
       v_allowed:=case when p_operation='answer' then array['expectedVersion','attemptId','choiceId']
         when p_operation in ('quiz-timeout','ai-answer') then array['expectedVersion','attemptId']
         when p_operation='purchase' then array['expectedVersion','tileId','action']
         when p_operation='ai-purchase' then array['expectedVersion','tileId']
         when p_operation in ('complete-turn','ai-complete-turn','island-skip','ai-island-skip')
           then array['expectedVersion','turnId']
         else array['expectedVersion'] end;
       if (p_body - v_allowed)<>'{}'::jsonb
         or jsonb_typeof(p_body->'expectedVersion') is distinct from 'number'
         or (p_body->>'expectedVersion') !~ '^[0-9]{1,10}$' then perform finpop_private.game_fail('INVALID_REQUEST'); end if;
       if (p_body->>'expectedVersion')::numeric<>g.state_version then perform finpop_private.game_fail('STATE_CONFLICT'); end if;
       if p_operation='pause' and g.status<>'ACTIVE' then perform finpop_private.game_fail('PAUSE_NOT_ALLOWED'); end if;
       if p_operation='resume' and g.status<>'PAUSED' then perform finpop_private.game_fail('GAME_NOT_PAUSED'); end if;
       if p_operation='abandon' and g.status not in ('ACTIVE','PAUSED') then perform finpop_private.game_fail('GAME_ALREADY_FINISHED'); end if;
       if p_operation not in ('pause','resume','abandon') and g.status<>'ACTIVE' then perform finpop_private.game_fail('GAME_NOT_ACTIVE'); end if;
       if g.rules_version<>'1.0' then perform finpop_private.game_fail('CONFIG_UNAVAILABLE'); end if;
       if p_operation in ('pause','resume','abandon') then
         -- 사용자 잠금과 게임 행 잠금을 얻은 뒤 시각을 확정합니다.
         v_now:=clock_timestamp();
         if p_operation='pause' then
           if g.mode<>'AI' then perform finpop_private.game_fail('PAUSE_NOT_ALLOWED'); end if;
           select * into q from public.game_quiz_attempts where game_id=g.id and resolved_at is null for update;
           if found then
             if q.deadline_at<=v_now then
               -- 마감된 문항을 일시정지로 살리지 않습니다. 판정·정산은 기존 서버 로직을 재사용합니다.
               perform finpop_private.resolve_game_quiz(g.id,q.player_id,q.id,null,true,false);
             else
               v_remaining:=least(20000,greatest(1,ceil(extract(epoch from (q.deadline_at-v_now))*1000)::integer));
               update public.game_quiz_attempts set remaining_millis=v_remaining where id=q.id;
             end if;
           end if;
           update public.game_sessions set status='PAUSED',paused_at=v_now,
             state_version=state_version+1,updated_at=v_now where id=g.id;
         elsif p_operation='resume' then
           update public.game_quiz_attempts set deadline_at=v_now+remaining_millis*interval '1 millisecond',remaining_millis=null
             where game_id=g.id and resolved_at is null and remaining_millis is not null;
           update public.game_sessions set status='ACTIVE',paused_at=null,
             state_version=state_version+1,updated_at=v_now where id=g.id;
         else
           -- 포기는 진행만 닫습니다. 계정 지갑·포인트·완료 횟수는 변경하지 않습니다.
           update public.game_turns set completed_at=v_now where game_id=g.id and completed_at is null;
           update public.game_sessions set status='ABANDONED',phase='ABANDONED',abandoned_at=v_now,paused_at=null,
             pending_purchase=null,pending_event=null,current_player_id=null,current_turn_id=null,
             state_version=state_version+1,updated_at=v_now where id=g.id;
         end if;
         v_data:=finpop_private.game_snapshot(g.id);
       elsif p_operation='initiative' then
         if g.phase<>'INITIATIVE_PENDING' then perform finpop_private.game_fail('INITIATIVE_ALREADY_RESOLVED'); end if;
         for i in 1..1000 loop
           v_a:=finpop_private.roll_die(); v_b:=finpop_private.roll_die();
           v_rolls:=v_rolls||jsonb_build_array(jsonb_build_object('P1',v_a,'P2',v_b));
           exit when v_a<>v_b;
         end loop;
         if v_a=v_b then perform finpop_private.game_fail('DICE_UNAVAILABLE'); end if;
         v_first:=case when v_a>v_b then 'P1' else 'P2' end;
         insert into public.game_turns(game_id,player_id) values(g.id,v_first) returning id into v_turn;
         update public.game_sessions set current_player_id=v_first,current_turn_id=v_turn,phase='TURN_READY',
           state_version=state_version+1,updated_at=clock_timestamp() where id=g.id;
         v_data:=jsonb_build_object('rolls',v_rolls,'firstPlayerId',v_first,'game',finpop_private.game_snapshot(g.id));
       elsif p_operation in ('purchase','ai-purchase') then
         v_actor:=case when p_operation='ai-purchase' then 'P2' else 'P1' end;
         if g.current_player_id is distinct from v_actor then perform finpop_private.game_fail('NOT_YOUR_TURN'); end if;
         if jsonb_typeof(p_body->'tileId') is distinct from 'number' or (p_body->>'tileId') !~ '^([0-9]|1[0-3])$'
          then perform finpop_private.game_fail('INVALID_REQUEST'); end if;
         if p_operation='purchase' and (jsonb_typeof(p_body->'action') is distinct from 'string'
           or p_body->>'action' not in ('BUY','PASS')) then perform finpop_private.game_fail('INVALID_REQUEST'); end if;
         v_result:=finpop_private.purchase_game_tile(g.id,v_actor,(p_body->>'tileId')::integer,
           p_body->>'action',p_operation='ai-purchase');
         v_data:=jsonb_build_object('purchaseEvent',v_result,'game',finpop_private.game_snapshot(g.id));
       elsif p_operation in ('complete-turn','ai-complete-turn','island-skip','ai-island-skip') then
         v_actor:=case when p_operation in ('ai-complete-turn','ai-island-skip') then 'P2' else 'P1' end;
         if g.current_player_id is distinct from v_actor then perform finpop_private.game_fail('NOT_YOUR_TURN'); end if;
         if jsonb_typeof(p_body->'turnId') is distinct from 'string'
           or (p_body->>'turnId') !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
           then perform finpop_private.game_fail('INVALID_REQUEST'); end if;
         v_result:=finpop_private.complete_game_turn(g.id,v_actor,(p_body->>'turnId')::uuid,
           p_operation in ('island-skip','ai-island-skip'));
         v_data:=jsonb_build_object('turnResult',v_result,'game',finpop_private.game_snapshot(g.id));
       elsif p_operation in ('answer','quiz-timeout','ai-answer') then
         v_actor:=case when p_operation='ai-answer' then 'P2' else 'P1' end;
         if g.current_player_id is distinct from v_actor then perform finpop_private.game_fail('NOT_YOUR_TURN'); end if;
         if g.mode<>'AI' then perform finpop_private.game_fail('INVALID_PHASE'); end if;
         if jsonb_typeof(p_body->'attemptId') is distinct from 'string'
           or (p_body->>'attemptId') !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
           then perform finpop_private.game_fail('INVALID_REQUEST'); end if;
         if p_operation='answer' and (jsonb_typeof(p_body->'choiceId') is distinct from 'number'
           or (p_body->>'choiceId') !~ '^[1-4]$') then perform finpop_private.game_fail('INVALID_REQUEST'); end if;
         v_result:=finpop_private.resolve_game_quiz(g.id,v_actor,(p_body->>'attemptId')::uuid,
           case when p_operation='answer' then (p_body->>'choiceId')::integer else null end,
           p_operation='quiz-timeout',p_operation='ai-answer');
         v_data:=jsonb_build_object('result',v_result,'game',finpop_private.game_snapshot(g.id));
       else
         v_actor:=case when p_operation='ai-roll' then 'P2' else 'P1' end;
         if g.mode<>'AI' or g.current_player_id is distinct from v_actor then perform finpop_private.game_fail('NOT_YOUR_TURN'); end if;
         if g.phase<>'TURN_READY' or g.current_turn_id is null then perform finpop_private.game_fail('INVALID_PHASE'); end if;
         select * into p from public.game_players where game_id=g.id and player_id=v_actor for update;
         if not found or p.is_ai is distinct from (v_actor='P2') then perform finpop_private.game_fail('INVALID_PHASE'); end if;
         if p.remaining_turns<=0 or p.island_wait_turns>0 then perform finpop_private.game_fail('INVALID_PHASE'); end if;
         -- 나머지 9종의 능력은 명세 구현 후 지원합니다. 미구현 능력을 생략하지 않습니다.
         if p.character_id<>'CHEESE' then perform finpop_private.game_fail('ABILITY_UNAVAILABLE'); end if;
         if exists(select 1 from public.game_turns where id=g.current_turn_id and
           (dice_value is not null or completed_at is not null)) then perform finpop_private.game_fail('INVALID_PHASE'); end if;
         v_dice:=finpop_private.roll_die();
         select jsonb_agg((p.position+step)%14 order by step) into v_path from generate_series(1,v_dice) step;
         v_position:=(p.position+v_dice)%14;
         v_bonus:=30+5*(p.character_level-1);
         v_events:=jsonb_build_array(jsonb_build_object('type','ABILITY_GOLD','playerId',v_actor,
           'amount',v_bonus,'abilityId','GOLDEN_POP'));
         if p.position+v_dice>=14 then
           v_bonus:=v_bonus+200;
           v_events:=v_events||jsonb_build_array(jsonb_build_object('type','START_REWARD','playerId',v_actor,'amount',200));
         end if;
         v_tile:=finpop_private.game_config()->'tiles'->v_position;
         v_topic:=v_tile->>'topic';
         select owner_player_id into v_owner from public.game_tiles where game_id=g.id and tile_id=v_position;
         if v_topic='GOLDEN_KEY' then perform finpop_private.game_fail('EVENT_RULES_UNAVAILABLE'); end if;
         if v_topic not in ('START','ISLAND') and v_owner is distinct from v_actor then
           select v.*, root.topic, root.source_type, answer.correct_choice_id, answer.explanation,
             (select jsonb_agg(jsonb_build_object('id',s.id,'title',s.title,'url',s.url,
               'publishedAt',s.published_at,'retrievedAt',s.retrieved_at) order by s.id)
              from public.quiz_version_sources link join public.quiz_sources s on s.id=link.source_id
              where link.question_version_id=v.id) as source
             into q
             from public.quiz_question_versions v
             join public.quiz_questions root on root.id=v.question_id
             join finpop_private.quiz_answer_keys answer on answer.question_version_id=v.id
             where v.review_status='APPROVED' and root.topic=v_topic
             and v.stage=g.quiz_stage and not exists(select 1 from public.game_quiz_attempts a
               where a.game_id=g.id and a.question_id=v.question_id)
             order by v.id limit 1 for share of v,root,answer;
           if not found then perform finpop_private.game_fail('QUIZ_POOL_EXHAUSTED'); end if;
           if not(q.choices @> '[{"id":1}]'::jsonb and q.choices @> '[{"id":2}]'::jsonb
             and q.choices @> '[{"id":3}]'::jsonb and q.choices @> '[{"id":4}]'::jsonb)
             or exists(select 1 from jsonb_array_elements(q.choices) c where
               jsonb_typeof(c->'text') is distinct from 'string') then perform finpop_private.game_fail('INVALID_QUIZ'); end if;
           v_issued:=clock_timestamp();
           insert into public.game_quiz_attempts(game_id,turn_id,player_id,question_id,question_version_id,tile_id,
             topic,source_type,stage,question,hint,choices,issued_at,deadline_at)
             values(g.id,g.current_turn_id,v_actor,q.question_id,q.id,v_position,q.topic,q.source_type,q.stage,q.question,q.hint,
               q.choices,v_issued,v_issued+interval '20 seconds') returning id into v_attempt;
           insert into finpop_private.game_quiz_answers(attempt_id,correct_choice_id,explanation,source)
             values(v_attempt,q.correct_choice_id,q.explanation,q.source);
         end if;
         update public.game_players set position=v_position,game_gold=game_gold+v_bonus,
           ability_state=jsonb_set(ability_state,'{triggerCount}',
             to_jsonb(coalesce((ability_state->>'triggerCount')::integer,0)+1)),
           island_wait_turns=case when v_topic='ISLAND' and remaining_turns>1 then 1 else island_wait_turns end
           where game_id=g.id and player_id=v_actor;
         update public.game_turns set dice_value=v_dice,movement_path=v_path where id=g.current_turn_id;
         update public.game_sessions set phase=case when v_attempt is not null then 'QUIZ_OPEN' else 'TURN_REVIEW' end,
           state_version=state_version+1,updated_at=clock_timestamp() where id=g.id;
         v_data:=jsonb_build_object('playerId',v_actor,'diceValue',v_dice,'movementPath',v_path,'events',v_events,
           'game',finpop_private.game_snapshot(g.id));
       end if;
     end if;
   else perform finpop_private.game_fail('INVALID_REQUEST');
   end if;
   v_envelope:=jsonb_build_object('data',v_data,'meta',v_meta);
 exception when sqlstate 'P0001' then
   v_code:=sqlerrm;
   v_status:=case
     when v_code='FORBIDDEN' then 403 when v_code='RESOURCE_NOT_FOUND' then 404
     when v_code='CHARACTER_LOCKED' then 422 when v_code='INVALID_REQUEST' then 400
     when v_code in ('CONFIG_UNAVAILABLE','DICE_UNAVAILABLE','ABILITY_UNAVAILABLE',
       'EVENT_RULES_UNAVAILABLE','QUIZ_POOL_EXHAUSTED','INVALID_QUIZ') then 503 else 409 end;
   v_envelope:=jsonb_build_object('error',jsonb_build_object('code',v_code,'message',
     case v_code when 'ACTIVE_GAME_EXISTS' then '진행 중인 게임을 이어하거나 포기해 주세요.'
       when 'CHARACTER_LOCKED' then '아직 해금되지 않은 캐릭터입니다.'
       when 'INSUFFICIENT_GOLD' then '칸을 구매할 G가 부족합니다. 패스를 선택해 주세요.'
       when 'PAUSE_NOT_ALLOWED' then '진행 중인 AI 대결만 일시정지할 수 있습니다.'
       when 'GAME_NOT_PAUSED' then '일시정지한 대결만 재개할 수 있습니다.'
       when 'GAME_ALREADY_FINISHED' then '이미 종료하거나 포기한 대결입니다.'
       when 'GAME_NOT_ACTIVE' then '일시정지한 대결은 재개한 뒤 진행해 주세요.'
       when 'STATE_CONFLICT' then '최신 게임 상태를 확인해 주세요.'
       when 'QUIZ_POOL_EXHAUSTED' then '해당 주제의 승인된 미출제 문항이 부족합니다.'
       when 'ABILITY_UNAVAILABLE' then '이 캐릭터의 서버 능력 처리는 아직 연결하지 않았습니다.'
       when 'EVENT_RULES_UNAVAILABLE' then '황금열쇠 서버 효과 처리는 아직 연결하지 않았습니다.'
       else '요청을 처리할 수 없습니다. 상태와 입력을 확인해 주세요.' end,
     'details',case when v_code='ACTIVE_GAME_EXISTS' then jsonb_build_object('activeGameId',v_active) else '{}'::jsonb end),'meta',v_meta);
 end;
 if v_mutation then
   if v_status=200 then v_cache_game:=v_game_id; end if;
   insert into finpop_private.game_action_requests(user_id,request_key,operation,request_body,game_id,http_status,response_body)
     values(p_user_id,p_request_key,p_operation,v_fingerprint,v_cache_game,v_status,v_envelope);
 end if;
 return jsonb_build_object('httpStatus',v_status,'body',v_envelope);
end;
$$;
revoke all on function public.finpop_game_action(uuid,text,uuid,uuid,jsonb),finpop_private.game_snapshot(uuid) from public,anon,authenticated;
grant execute on function public.finpop_game_action(uuid,text,uuid,uuid,jsonb),finpop_private.game_snapshot(uuid) to service_role;
commit;
