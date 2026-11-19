-- groundwork-short-codes: a BEFORE INSERT row trigger that gives the row a
-- short code when it comes without one. Its arguments: the kind's letter,
-- the column's name, the number of random characters. A row that comes
-- with a code keeps it (the unique index still refuses a copy's).
CREATE OR REPLACE FUNCTION public.groundwork_short_codes_fill_code()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF to_jsonb(NEW) ->> TG_ARGV[1] IS NULL THEN
    NEW := jsonb_populate_record(
             NEW,
             jsonb_build_object(TG_ARGV[1], public.groundwork_short_codes_make_code(TG_ARGV[0], TG_ARGV[2]::integer)));
  END IF;
  RETURN NEW;
END
$$;
