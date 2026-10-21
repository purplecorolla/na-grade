-- Until #11 the tokens were stored in plain text (local databases only). They can't be encrypted here: the key
-- never reaches the database. Users connect the provider again
DELETE FROM connected_accounts;
