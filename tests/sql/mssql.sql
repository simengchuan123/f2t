create table demo (
    uuid uniqueidentifier not null,
    id int identity not null,
    [name] varchar(100),
    localname nvarchar(200),
    [description] text,
    localdescription ntext,
    age tinyint,
    smallnumber smallint,
    [number] int,
    long bigint,
    floatnumber float,
    doublenumber real,
    decimaldefault decimal,
    decimalnumber decimal(20,5),
    tsdate date,
    tstime time,
    tsdt datetime,
    tsdt2 datetime2,
    tsdtoffset datetimeoffset,
    primary key(id, uuid),
    unique([name], age),
    unique([number], age, long)
)
