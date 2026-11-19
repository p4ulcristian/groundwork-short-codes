-- groundwork-short-codes: a new short code, `letter` and then
-- `random_length` characters from 31 (A-Z without I, L, O; 2-9). Each
-- character comes from two bytes of a fresh gen_random_uuid() (Postgres's
-- strong random, no extension), so 65536 % 31 leaves a bias of 2 in 65536.
CREATE OR REPLACE FUNCTION public.groundwork_short_codes_make_code(letter text, random_length integer)
RETURNS text
LANGUAGE plpgsql
VOLATILE
AS $$
DECLARE
  alphabet constant text := 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
  code text := letter;
  bytes bytea;
BEGIN
  FOR i IN 1..random_length LOOP
    bytes := uuid_send(gen_random_uuid());
    code := code || substr(alphabet, 1 + ((get_byte(bytes, 0) * 256 + get_byte(bytes, 1)) % 31), 1);
  END LOOP;
  RETURN code;
END
$$;
